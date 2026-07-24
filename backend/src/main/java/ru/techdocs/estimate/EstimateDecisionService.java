package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Память эталонных решений: для уникального оборудования и категории операции —
 * какая расценка и периодичность. Наполняется загрузкой эталона и кнопкой
 * «В эталон»; при сборке сметы используется вместо ИИ.
 */
@Service
@RequiredArgsConstructor
public class EstimateDecisionService {

    private final EstimateRateDecisionRepository repository;
    private final UniqueEquipmentService uniqueEquipmentService;
    private final EstimateRowRepository rowRepository;

    /** Данные строки для сохранения решения. */
    public record DecisionData(String equipmentName, String model, String manufacturer,
                               String operationName, String rateCode, String rateName,
                               String periodicity, BigDecimal perYear, BigDecimal correction) {}

    /**
     * Категория операции для ключа: осмотр / ТО / замена / контроль / проверка / …
     * Огрубление нужно, чтобы решения из разных источников (эталон, одобренная смета)
     * по одной и той же работе совпадали.
     */
    public static String operationKey(String operationName) {
        String s = operationName == null ? "" : operationName.toLowerCase().replace('ё', 'е');
        if (s.contains("осмотр")) return "осмотр";
        if (s.contains("замен")) return "замена";
        if (s.contains("ремонт")) return "ремонт";
        if (s.contains("контрол")) return "контроль";
        if (s.contains("проверк")) return "проверка";
        if (s.contains("наладк")) return "наладка";
        if (s.contains("обслуж")) return "то";
        String norm = s.replaceAll("[\\s\\u00A0]+", " ").strip();
        if (norm.isBlank()) return "прочее";
        return norm.length() > 200 ? norm.substring(0, 200) : norm;
    }

    /** Решения для оборудования (по уникальному id). */
    public List<EstimateRateDecision> lookup(Long uniqueEquipmentId) {
        return uniqueEquipmentId == null ? List.of()
                : repository.findByUniqueEquipmentIdOrderByOperationKey(uniqueEquipmentId);
    }

    /** Уникальное оборудование по описанию (без создания). */
    public Long resolveUniqueId(String name, String model, String manufacturer) {
        return uniqueEquipmentService.find(name, model, manufacturer)
                .map(UniqueEquipment::getId).orElse(null);
    }

    /** Сохраняет/обновляет решение; создаёт уникальное оборудование, если его ещё нет. */
    public EstimateRateDecision upsert(DecisionData d, String source) {
        if (d.rateCode() == null || d.rateCode().isBlank()) return null; // без расценки — не эталон
        UniqueEquipment ue = uniqueEquipmentService.resolve(d.equipmentName(), d.model(), d.manufacturer());
        String key = operationKey(d.operationName());
        EstimateRateDecision decision = repository
                .findByUniqueEquipmentIdAndOperationKey(ue.getId(), key)
                .orElseGet(EstimateRateDecision::new);
        decision.setUniqueEquipmentId(ue.getId());
        decision.setOperationKey(key);
        decision.setOperationName(d.operationName());
        decision.setRateCode(d.rateCode().strip());
        decision.setRateName(d.rateName());
        decision.setPeriodicity(d.periodicity());
        decision.setPerYear(d.perYear());
        decision.setCorrection(d.correction());
        decision.setSource(source);
        decision.setUpdatedAt(Instant.now());
        return repository.save(decision);
    }

    /** «В эталон»: сохраняет строки сметы в память (source=APPROVED). */
    public int promote(Long estimateId) {
        int saved = 0;
        for (EstimateRow row : rowRepository.findByEstimateIdOrderByPosition(estimateId)) {
            if (row.getRateCode() == null || row.getRateCode().isBlank()) continue;
            DecisionData d = new DecisionData(row.getEquipmentName(), row.getEquipmentType(),
                    row.getManufacturer(), row.getOperationName(), row.getRateCode(), row.getRateName(),
                    row.getPeriodicity(), row.getOpsPerYear(), row.getCorrection());
            if (upsert(d, EstimateRateDecision.SOURCE_APPROVED) != null) saved++;
        }
        return saved;
    }

    public List<EstimateRateDecision> all() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }
}
