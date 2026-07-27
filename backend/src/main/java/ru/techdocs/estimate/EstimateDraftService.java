package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;
import ru.techdocs.normative.NormativeAiMatchService;
import ru.techdocs.normative.NormativeRate;
import ru.techdocs.normative.NormativeRateRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ИИ-черновик сметы: по реестру оборудования объекта формирует строки —
 * подбор расценки СН-2012 (через ИИ по каталогу), периодичность из
 * паспорта/ПКМ/названия расценки, автозаполнение цен. Результат — черновик
 * для ручной правки.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EstimateDraftService {

    private final EstimateService estimateService;
    private final EstimateRowRepository rowRepository;
    private final EquipmentRepository equipmentRepository;
    private final EngineeringSystemRepository systemRepository;
    private final NormativeAiMatchService aiMatchService;
    private final EquipmentMaintenanceResolver maintenanceResolver;
    private final EstimateDecisionService decisionService;
    private final NormativeRateRepository rateRepository;

    private static final int EXAMPLE_LIMIT = 20;   // сколько эталонных примеров подкладывать ИИ

    public record DraftResult(int created, int skipped, boolean aiUsed) {}

    /** Выбор расценки для кэша консистентности «по типу» в пределах одной сметы. */
    private record RatePick(String rateCode, String source, boolean needsReview) {
        RatePick(String rateCode, String source) { this(rateCode, source, false); }
    }

    /** Ключ типа: система + наименование (без модели) + категория операции. */
    private String typeKey(String system, String name, String operationName) {
        String sys = ru.techdocs.common.SystemNormalizer.canonical(system);
        String nm = name == null ? "" : name.toLowerCase().replace('ё', 'е').replaceAll("\\s+", " ").strip();
        return (sys == null ? "" : sys) + "|" + nm + "|" + EstimateDecisionService.operationKey(operationName);
    }

    public DraftResult generate(Long estimateId) {
        return generate(estimateId, null);
    }

    /**
     * Черновик по выбранным системам объекта. Строки каждой системы получают
     * раздел (section) с её названием — в смете и выгрузке это отдельные блоки
     * (например, АПС и СОУЭ). Пустой список систем — всё оборудование объекта.
     */
    public DraftResult generate(Long estimateId, List<Long> systemIds) {
        Estimate estimate = estimateService.get(estimateId);
        List<Equipment> equipment = new ArrayList<>();
        if (systemIds != null && !systemIds.isEmpty()) {
            for (Long sid : systemIds) {
                equipment.addAll(equipmentRepository.findFiltered(estimate.getFacilityId(), sid));
            }
        } else {
            equipment.addAll(equipmentRepository.findFiltered(estimate.getFacilityId(), estimate.getSystemId()));
        }

        // не дублируем оборудование, уже присутствующее в смете
        Set<Long> existing = new HashSet<>();
        for (EstimateRow row : rowRepository.findByEstimateIdOrderByPosition(estimateId)) {
            if (row.getEquipmentId() != null) existing.add(row.getEquipmentId());
        }

        boolean aiAvailable = aiMatchService.isAvailable();
        // few-shot примеры из эталона по системам и кэш выбора «по типу» в пределах сметы
        Map<String, List<NormativeAiMatchService.Example>> examplesCache = new HashMap<>();
        Map<String, RatePick> consistency = new HashMap<>();
        int created = 0, skipped = 0;
        for (Equipment eq : equipment) {
            if (existing.contains(eq.getId())) { skipped++; continue; }

            String systemType = systemName(eq.getEngineeringSystemId());

            // 1) память решений (эталоны): то же оборудование уже считали — берём готовое,
            //    без ИИ, консистентно. По строке на каждое запомненное решение.
            Long uniqueId = eq.getUniqueEquipmentId() != null ? eq.getUniqueEquipmentId()
                    : decisionService.resolveUniqueId(eq.getName(), eq.getModel(), eq.getManufacturer(), systemType);
            List<EstimateRateDecision> decisions = decisionService.lookup(uniqueId);
            if (!decisions.isEmpty()) {
                for (EstimateRateDecision d : decisions) {
                    addRowFromDecision(estimateId, eq, systemType, d);
                    // из памяти пополняем кэш «по типу»: новые модели того же типа возьмут ту же расценку
                    consistency.putIfAbsent(typeKey(systemType, eq.getName(), d.getOperationName()),
                            new RatePick(d.getRateCode(), "LEARNED", false));
                    created++;
                }
                continue;
            }

            // 2) паспорт/ПКМ + ИИ-подбор расценки (с few-shot из эталона и консистентностью по типу)
            List<NormativeAiMatchService.Example> examples = examplesCache.computeIfAbsent(
                    systemType, s -> decisionService.examplesForSystem(s, EXAMPLE_LIMIT));
            List<EquipmentMaintenanceResolver.Planned> operations =
                    maintenanceResolver.resolveOperations(eq, systemType);
            if (operations.isEmpty()) {
                addRowForOperation(estimateId, eq, systemType, null, examples, consistency);
                created++;
            } else {
                for (EquipmentMaintenanceResolver.Planned op : operations) {
                    addRowForOperation(estimateId, eq, systemType, op, examples, consistency);
                    created++;
                }
            }
        }
        log.info("Черновик сметы {}: создано {} строк, пропущено {} (ИИ: {})",
                estimateId, created, skipped, aiAvailable);
        return new DraftResult(created, skipped, aiAvailable);
    }

    private void addRowForOperation(Long estimateId, Equipment eq, String systemType,
                                    EquipmentMaintenanceResolver.Planned op,
                                    List<NormativeAiMatchService.Example> examples,
                                    Map<String, RatePick> consistency) {
        String operationName = op != null ? op.operationName() : operationName(eq);
        String tkey = typeKey(systemType, eq.getName(), operationName);

        // Консистентность «по типу» в пределах сметы: если оборудование с таким же
        // наименованием и операцией уже получило расценку (из эталона или от ИИ) —
        // берём ту же, без повторного обращения к ИИ. Гарантирует единый выбор для
        // одного типа и экономит запросы.
        RatePick cached = consistency.get(tkey);
        String rateCode;
        String source;
        boolean needsReview;
        String note = op != null ? op.note() : "периодичность из наименования расценки";
        String justification;
        NormativeRate rate;
        if (cached != null) {
            rateCode = cached.rateCode();
            source = cached.source();
            needsReview = cached.needsReview();
            rate = rateCode == null ? null : catalogRate(rateCode);
            justification = "Расценка согласована с оборудованием того же типа в этой смете. Периодичность: " + note;
        } else {
            // подбор расценки под конкретную операцию (с few-shot примерами из эталона)
            String query = op == null ? describe(eq) : describe(eq) + " " + op.operationName();
            var match = aiMatchService.match(query, examples);
            //  - ИИ подобрал → берём его расценку (AI);
            //  - ИИ настроен, но не подобрал/упал → шифр пустой, строка «на проверку» (AI_FAILED);
            //  - ИИ выключен → верхний результат поиска как подсказка, тоже «на проверку» (CATALOG).
            if (!match.matches().isEmpty()) {
                rate = match.matches().get(0).rate();
                source = "AI";
                needsReview = false;
            } else if (match.aiConfigured()) {
                rate = null;
                source = "AI_FAILED";
                needsReview = true;
            } else {
                rate = match.candidates().isEmpty() ? null : match.candidates().get(0);
                source = "CATALOG";
                needsReview = true;
            }
            rateCode = rate == null ? null : rate.getCode();
            justification = justification(match, note, source);
            consistency.put(tkey, new RatePick(rateCode, source, needsReview));
        }

        // периодичность: из операции (паспорт/ПКМ), иначе из наименования расценки
        BigDecimal perYear = op != null && op.perYear() != null
                ? op.perYear()
                : maintenanceResolver.perYearFromRate(rate == null ? null : rate.getName());
        String periodicityText = op != null && op.periodicityText() != null
                ? op.periodicityText()
                : maintenanceResolver.label(perYear);

        EstimateService.RowInput input = new EstimateService.RowInput(
                systemType, eq.getId(), eq.getName(), eq.getModel(), eq.getManufacturer(),
                operationName,
                rateCode,
                null,
                periodicityText,
                justification,
                perYear,
                eq.getQuantity(),
                null, null, null, null, null,
                null, null,
                needsReview, source);
        estimateService.addRow(estimateId, input);
    }

    private NormativeRate catalogRate(String code) {
        return rateRepository.findFirstByCodeOrderById(code).orElse(null);
    }

    /** Строка из эталонного решения — расценка и периодичность известны, ИИ не нужен. */
    private void addRowFromDecision(Long estimateId, Equipment eq, String systemType, EstimateRateDecision d) {
        String note = d.getSource().equals(EstimateRateDecision.SOURCE_REFERENCE)
                ? "из эталонной сметы" : "из ранее одобренной сметы";
        EstimateService.RowInput input = new EstimateService.RowInput(
                systemType, eq.getId(), eq.getName(), eq.getModel(), eq.getManufacturer(),
                d.getOperationName() != null ? d.getOperationName() : operationName(eq),
                d.getRateCode(),
                null,
                d.getPeriodicity(),
                "Расценка и периодичность " + note + " (эталон).",
                d.getPerYear(),
                eq.getQuantity(),
                null, null, null, null, null,
                d.getCorrection(), null,
                false, "LEARNED");
        estimateService.addRow(estimateId, input);
    }

    private String describe(Equipment eq) {
        StringBuilder sb = new StringBuilder("техническое обслуживание ");
        if (eq.getName() != null) sb.append(eq.getName()).append(' ');
        if (eq.getModel() != null) sb.append(eq.getModel()).append(' ');
        if (eq.getManufacturer() != null) sb.append(eq.getManufacturer());
        return sb.toString().strip();
    }

    private String operationName(Equipment eq) {
        String name = eq.getName() == null || eq.getName().isBlank() ? "оборудования" : eq.getName();
        return "Техническое обслуживание — " + name;
    }

    private String justification(NormativeAiMatchService.MatchResult match, String periodicityNote, String source) {
        StringBuilder sb = new StringBuilder();
        switch (source) {
            case "AI" -> {
                String reason = match.matches().isEmpty() ? null : match.matches().get(0).reason();
                sb.append(reason != null ? "Расценка (ИИ): " + reason + ". " : "Расценка подобрана ИИ. ");
            }
            case "AI_FAILED" -> sb.append("⚠ НА ПРОВЕРКУ: ИИ не подобрал расценку — выберите вручную. ");
            case "CATALOG" -> sb.append("⚠ НА ПРОВЕРКУ: ИИ выключен, расценка — верхний результат поиска по каталогу. ");
            default -> { /* нет источника — только периодичность */ }
        }
        sb.append("Периодичность: ").append(periodicityNote);
        return sb.toString().strip();
    }

    private String systemName(Long systemId) {
        if (systemId == null) return null;
        return systemRepository.findById(systemId).map(EngineeringSystem::getName).orElse(null);
    }
}
