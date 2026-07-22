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
        Estimate estimate = estimateService.get(estimateId);
        List<Equipment> equipment =
                equipmentRepository.findFiltered(estimate.getFacilityId(), estimate.getSystemId());

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
            var match = aiMatchService.match(describe(eq));
            NormativeRate rate = pickRate(match);
            EquipmentMaintenanceResolver.Planned plan =
                    maintenanceResolver.resolve(eq, systemType, rate == null ? null : rate.getName());

            EstimateService.RowInput input = new EstimateService.RowInput(
                    systemType,                       // section
                    eq.getId(),                       // equipmentId
                    eq.getName(),                     // equipmentName
                    eq.getModel(),                    // equipmentType
                    eq.getManufacturer(),             // manufacturer
                    operationName(eq),                // operationName (E)
                    rate == null ? null : rate.getCode(), // rateCode → автозаполнение цен
                    null,                             // rateName (из каталога)
                    plan.periodicityText(),           // periodicity
                    justification(match, plan),       // justification
                    plan.perYear(),                   // opsPerYear
                    eq.getQuantity(),                 // qty
                    null, null, null, null, null,     // unitBasis/цены — из каталога
                    null, null);                      // correction/laborHours
            estimateService.addRow(estimateId, input);
            created++;
        }
        log.info("Черновик сметы {}: создано {} строк, пропущено {} (ИИ: {})",
                estimateId, created, skipped, aiAvailable);
        return new DraftResult(created, skipped, aiAvailable);
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

    private String justification(NormativeAiMatchService.MatchResult match, EquipmentMaintenanceResolver.Planned plan) {
        StringBuilder sb = new StringBuilder();
        if (!match.matches().isEmpty() && match.matches().get(0).reason() != null) {
            sb.append("Расценка (ИИ): ").append(match.matches().get(0).reason()).append(". ");
        } else if (match.aiUsed()) {
            sb.append("Расценка подобрана ИИ. ");
        } else if (!match.candidates().isEmpty()) {
            sb.append("Расценка — верхний результат поиска по каталогу. ");
        }
        sb.append("Периодичность: ").append(plan.note());
        return sb.toString().strip();
    }

    private String systemName(Long systemId) {
        if (systemId == null) return null;
        return systemRepository.findById(systemId).map(EngineeringSystem::getName).orElse(null);
    }
}
