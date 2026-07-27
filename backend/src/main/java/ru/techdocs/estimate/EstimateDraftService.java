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
    private record RatePick(String rateCode, String source, boolean needsReview,
                            String periodicityText, BigDecimal perYear) {}

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
        // эталон по системам (примеры + периодичность) и кэш выбора «по типу» в пределах сметы
        Map<String, EstimateDecisionService.SystemEtalon> etalonCache = new HashMap<>();
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
                            new RatePick(d.getRateCode(), "LEARNED", false, d.getPeriodicity(), d.getPerYear()));
                    created++;
                }
                continue;
            }

            // 2) паспорт/ПКМ + ИИ-подбор расценки (с few-shot из эталона и консистентностью по типу)
            EstimateDecisionService.SystemEtalon etalon = etalonCache.computeIfAbsent(
                    systemType, s -> decisionService.systemEtalon(s, EXAMPLE_LIMIT));
            List<EquipmentMaintenanceResolver.Planned> operations =
                    maintenanceResolver.resolveOperations(eq, systemType);
            if (operations.isEmpty()) {
                addRowForOperation(estimateId, eq, systemType, null, etalon, consistency);
                created++;
            } else {
                for (EquipmentMaintenanceResolver.Planned op : operations) {
                    addRowForOperation(estimateId, eq, systemType, op, etalon, consistency);
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
                                    EstimateDecisionService.SystemEtalon etalon,
                                    Map<String, RatePick> consistency) {
        String operationName = op != null ? op.operationName() : operationName(eq);
        String tkey = typeKey(systemType, eq.getName(), operationName);
        String note = op != null ? op.note() : "периодичность из наименования расценки";

        // Консистентность «по типу» в пределах сметы: если оборудование с таким же
        // наименованием и операцией уже получило расценку (из эталона или от ИИ) —
        // берём ту же (и ту же периодичность), без повторного обращения к ИИ.
        RatePick cached = consistency.get(tkey);
        if (cached != null) {
            NormativeRate rate = cached.rateCode() == null ? null : catalogRate(cached.rateCode());
            BigDecimal perYear = cached.perYear() != null ? cached.perYear()
                    : maintenanceResolver.perYearFromRate(rate == null ? null : rate.getName());
            String periodicityText = cached.periodicityText() != null ? cached.periodicityText()
                    : maintenanceResolver.label(perYear);
            addRow(estimateId, eq, systemType, operationName, cached.rateCode(), periodicityText, perYear,
                    "Расценка согласована с оборудованием того же типа в этой смете. Периодичность: " + note,
                    cached.needsReview(), cached.source());
            return;
        }

        // подбор расценки под конкретную операцию (с few-shot примерами из эталона)
        String query = op == null ? describe(eq) : describe(eq) + " " + op.operationName();
        var match = aiMatchService.match(query, etalon.examples());
        //  - ИИ подобрал → AI; если шифр есть в эталоне системы → AI_ETALON (доверенный);
        //  - ИИ настроен, но не подобрал/упал → шифр пустой, «на проверку» (AI_FAILED);
        //  - ИИ выключен → верхний результат поиска как подсказка, «на проверку» (CATALOG).
        NormativeRate rate;
        String source;
        boolean needsReview;
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
        String rateCode = rate == null ? null : rate.getCode();

        // если ИИ выбрал расценку, которая есть в эталоне этой системы — это доверенный
        // выбор: помечаем AI_ETALON и берём периодичность из эталона (раз расценка оттуда)
        EstimateDecisionService.EtalonRate etalonRate = rateCode == null ? null : etalon.byRateCode().get(rateCode);
        BigDecimal perYear;
        String periodicityText;
        if (etalonRate != null && "AI".equals(source)) {
            source = "AI_ETALON";
            perYear = etalonRate.perYear() != null ? etalonRate.perYear()
                    : (op != null && op.perYear() != null ? op.perYear()
                       : maintenanceResolver.perYearFromRate(rate.getName()));
            periodicityText = etalonRate.periodicity() != null ? etalonRate.periodicity()
                    : maintenanceResolver.label(perYear);
        } else {
            perYear = op != null && op.perYear() != null ? op.perYear()
                    : maintenanceResolver.perYearFromRate(rate == null ? null : rate.getName());
            periodicityText = op != null && op.periodicityText() != null ? op.periodicityText()
                    : maintenanceResolver.label(perYear);
        }

        consistency.put(tkey, new RatePick(rateCode, source, needsReview, periodicityText, perYear));
        addRow(estimateId, eq, systemType, operationName, rateCode, periodicityText, perYear,
                justification(match, note, source), needsReview, source);
    }

    /** Общая сборка строки черновика. */
    private void addRow(Long estimateId, Equipment eq, String systemType, String operationName,
                        String rateCode, String periodicityText, BigDecimal perYear,
                        String justification, boolean needsReview, String source) {
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
            case "AI", "AI_ETALON" -> {
                String reason = match.matches().isEmpty() ? null : match.matches().get(0).reason();
                String prefix = "AI_ETALON".equals(source) ? "Расценка (ИИ, по эталону)" : "Расценка (ИИ)";
                sb.append(reason != null ? prefix + ": " + reason + ". " : prefix + ". ");
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
