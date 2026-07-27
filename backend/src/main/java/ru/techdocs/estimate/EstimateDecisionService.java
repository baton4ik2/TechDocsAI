package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Данные строки для сохранения решения (system — инженерная система строки/раздела). */
    public record DecisionData(String equipmentName, String model, String manufacturer, String system,
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

    /** Решение эталона: расценка + периодичность (для переиспользования по шифру или наименованию). */
    public record EtalonRate(String rateCode, String periodicity, BigDecimal perYear) {}

    /** Запись эталона системы: наименование/операция оборудования → решение (для нечёткого матча). */
    public record EtalonEntry(String name, String operationKey, EtalonRate rate) {}

    /**
     * Данные эталона по системе:
     *  - examples — few-shot примеры «оборудование → шифр» для ИИ;
     *  - byRateCode — «шифр → решение» (если ИИ выбрал эталонный шифр, берём и периодичность);
     *  - byNameOp — «каноническое наименование|операция → решение»: точное совпадение имени;
     *  - entries — все записи эталона системы (для нечёткого совпадения по словам имени).
     */
    public record SystemEtalon(List<ru.techdocs.normative.NormativeAiMatchService.Example> examples,
                               Map<String, EtalonRate> byRateCode, Map<String, EtalonRate> byNameOp,
                               List<EtalonEntry> entries) {}

    /** Каноническое наименование оборудования для сопоставления по типу. */
    public static String nameKey(String name) {
        return name == null ? "" : name.toLowerCase().replace('ё', 'е').replaceAll("\\s+", " ").strip();
    }

    public SystemEtalon systemEtalon(String system, int limit) {
        String canonical = ru.techdocs.common.SystemNormalizer.canonical(system);
        if (canonical == null) return new SystemEtalon(List.of(), Map.of(), Map.of(), List.of());
        List<ru.techdocs.normative.NormativeAiMatchService.Example> examples = new ArrayList<>();
        Map<String, EtalonRate> byRateCode = new HashMap<>();
        Map<String, EtalonRate> byNameOp = new HashMap<>();
        List<EtalonEntry> entries = new ArrayList<>();
        for (UniqueEquipment ue : uniqueEquipmentService.bySystemType(canonical)) {
            String name = ue.getName() == null ? "" : ue.getName();
            String descr = ue.getModel() == null || ue.getModel().isBlank() ? name : name + " " + ue.getModel();
            for (EstimateRateDecision d : repository.findByUniqueEquipmentIdOrderByOperationKey(ue.getId())) {
                if (d.getRateCode() == null || d.getRateCode().isBlank()) continue;
                EtalonRate er = new EtalonRate(d.getRateCode().strip(), d.getPeriodicity(), d.getPerYear());
                byRateCode.putIfAbsent(er.rateCode(), er);
                byNameOp.putIfAbsent(nameKey(name) + "|" + d.getOperationKey(), er);
                entries.add(new EtalonEntry(name, d.getOperationKey(), er));
                if (examples.size() < limit) {
                    examples.add(new ru.techdocs.normative.NormativeAiMatchService.Example(descr.strip(), d.getRateCode()));
                }
            }
        }
        return new SystemEtalon(examples, byRateCode, byNameOp, entries);
    }

    /**
     * Уникальное оборудование по описанию и системе (без создания). С запасным поиском
     * по модели без системы — чтобы переиспользование эталона не ломалось, если раздел
     * эталона не распознался как система.
     */
    public Long resolveUniqueId(String name, String model, String manufacturer, String system) {
        return uniqueEquipmentService.findWithFallback(name, model, manufacturer, system)
                .map(UniqueEquipment::getId).orElse(null);
    }

    /** Сохраняет/обновляет одно решение (обёртка над {@link #saveAll}). */
    public EstimateRateDecision upsert(DecisionData d, String source) {
        List<EstimateRateDecision> saved = saveAll(List.of(d), source);
        return saved.isEmpty() ? null : saved.get(0);
    }

    /**
     * Сохраняет набор решений в одной транзакции с дедупликацией по
     * (оборудование, категория операции): эталон/смета часто повторяют одно и то
     * же оборудование на многих строках — иначе повторная вставка упирается в
     * уникальный ключ (DataIntegrityViolation). Побеждает последняя строка.
     */
    @Transactional
    public List<EstimateRateDecision> saveAll(List<DecisionData> data, String source) {
        // сворачиваем к одному решению на (оборудование+система, операция)
        Map<String, DecisionData> deduped = new LinkedHashMap<>();
        for (DecisionData d : data) {
            if (d.rateCode() == null || d.rateCode().isBlank()) continue; // без расценки — не эталон
            String key = UniqueEquipmentService.normKey(d.equipmentName(), d.model(), d.manufacturer(), d.system())
                    + "|" + operationKey(d.operationName());
            deduped.put(key, d);
        }

        Map<String, UniqueEquipment> equipmentCache = new HashMap<>();
        List<EstimateRateDecision> result = new ArrayList<>();
        for (DecisionData d : deduped.values()) {
            String normKey = UniqueEquipmentService.normKey(d.equipmentName(), d.model(), d.manufacturer(), d.system());
            UniqueEquipment ue = equipmentCache.computeIfAbsent(normKey,
                    k -> uniqueEquipmentService.resolve(d.equipmentName(), d.model(), d.manufacturer(), d.system()));
            String opKey = operationKey(d.operationName());
            EstimateRateDecision decision = repository
                    .findByUniqueEquipmentIdAndOperationKey(ue.getId(), opKey)
                    .orElseGet(EstimateRateDecision::new);
            decision.setUniqueEquipmentId(ue.getId());
            decision.setOperationKey(opKey);
            decision.setOperationName(d.operationName());
            decision.setRateCode(d.rateCode().strip());
            decision.setRateName(d.rateName());
            decision.setPeriodicity(d.periodicity());
            decision.setPerYear(d.perYear());
            decision.setCorrection(d.correction());
            decision.setSource(source);
            decision.setUpdatedAt(Instant.now());
            result.add(repository.save(decision));
        }
        return result;
    }

    /** «В эталон»: сохраняет строки сметы в память (source=APPROVED). */
    public int promote(Long estimateId) {
        List<DecisionData> data = new ArrayList<>();
        for (EstimateRow row : rowRepository.findByEstimateIdOrderByPosition(estimateId)) {
            if (row.getRateCode() == null || row.getRateCode().isBlank()) continue;
            data.add(new DecisionData(row.getEquipmentName(), row.getEquipmentType(),
                    row.getManufacturer(), row.getSection(), row.getOperationName(), row.getRateCode(),
                    row.getRateName(), row.getPeriodicity(), row.getOpsPerYear(), row.getCorrection()));
        }
        return saveAll(data, EstimateRateDecision.SOURCE_APPROVED).size();
    }

    public List<EstimateRateDecision> all() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }
}
