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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    public record DraftResult(int created, int skipped, boolean aiUsed) {}

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
        int created = 0, skipped = 0;
        for (Equipment eq : equipment) {
            if (existing.contains(eq.getId())) { skipped++; continue; }

            String systemType = systemName(eq.getEngineeringSystemId());
            List<EquipmentMaintenanceResolver.Planned> operations =
                    maintenanceResolver.resolveOperations(eq, systemType);

            if (operations.isEmpty()) {
                // источников нет — одна общая операция ТО, периодичность из расценки
                addRowForOperation(estimateId, eq, systemType, null);
                created++;
            } else {
                // паспорт/ПКМ: по строке на каждую плановую операцию
                for (EquipmentMaintenanceResolver.Planned op : operations) {
                    addRowForOperation(estimateId, eq, systemType, op);
                    created++;
                }
            }
        }
        log.info("Черновик сметы {}: создано {} строк, пропущено {} (ИИ: {})",
                estimateId, created, skipped, aiAvailable);
        return new DraftResult(created, skipped, aiAvailable);
    }

    private void addRowForOperation(Long estimateId, Equipment eq, String systemType,
                                    EquipmentMaintenanceResolver.Planned op) {
        // подбор расценки под конкретную операцию (для паспорта/ПКМ учитываем её название)
        String query = op == null ? describe(eq) : describe(eq) + " " + op.operationName();
        var match = aiMatchService.match(query);
        NormativeRate rate = pickRate(match);

        // периодичность: из операции (паспорт/ПКМ), иначе из наименования расценки
        BigDecimal perYear = op != null && op.perYear() != null
                ? op.perYear()
                : maintenanceResolver.perYearFromRate(rate == null ? null : rate.getName());
        String periodicityText = op != null && op.periodicityText() != null
                ? op.periodicityText()
                : maintenanceResolver.label(perYear);
        String operationName = op != null ? op.operationName() : operationName(eq);
        String note = op != null ? op.note() : "периодичность из наименования расценки";

        EstimateService.RowInput input = new EstimateService.RowInput(
                systemType, eq.getId(), eq.getName(), eq.getModel(), eq.getManufacturer(),
                operationName,
                rate == null ? null : rate.getCode(),
                null,
                periodicityText,
                justification(match, note),
                perYear,
                eq.getQuantity(),
                null, null, null, null, null,
                null, null);
        estimateService.addRow(estimateId, input);
    }

    private NormativeRate pickRate(NormativeAiMatchService.MatchResult match) {
        if (!match.matches().isEmpty()) return match.matches().get(0).rate();
        if (!match.candidates().isEmpty()) return match.candidates().get(0);
        return null;
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

    private String justification(NormativeAiMatchService.MatchResult match, String periodicityNote) {
        StringBuilder sb = new StringBuilder();
        if (!match.matches().isEmpty() && match.matches().get(0).reason() != null) {
            sb.append("Расценка (ИИ): ").append(match.matches().get(0).reason()).append(". ");
        } else if (match.aiUsed()) {
            sb.append("Расценка подобрана ИИ. ");
        } else if (!match.candidates().isEmpty()) {
            sb.append("Расценка — верхний результат поиска по каталогу. ");
        }
        sb.append("Периодичность: ").append(periodicityNote);
        return sb.toString().strip();
    }

    private String systemName(Long systemId) {
        if (systemId == null) return null;
        return systemRepository.findById(systemId).map(EngineeringSystem::getName).orElse(null);
    }
}
