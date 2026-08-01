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
                               String periodicity, BigDecimal perYear, BigDecimal correction,
                               String justification) {}

    /**
     * Категория операции для ключа: осмотр / ТО / замена / контроль / проверка / …
     * Огрубление нужно, чтобы решения из разных источников (эталон, одобренная смета)
     * по одной и той же работе совпадали.
     */
    public static String operationKey(String operationName) {
        String s = operationName == null ? "" : operationName.toLowerCase().replace('ё', 'е');
        String base = category(s);
        if (base == null) {
            String norm = s.replaceAll("[\\s\\u00A0]+", " ").strip();
            if (norm.isBlank()) return "прочее";
            return norm.length() > 200 ? norm.substring(0, 200) : norm;
        }
        // Номер работы внутри категории: у одного оборудования бывает несколько разных
        // работ одной категории с РАЗНЫМИ расценками («Техническое обслуживание 1 / 2 /
        // 3» — 106-1, 106-2, 106-3). Без номера они схлопывались в одно решение.
        var m = java.util.regex.Pattern
                .compile("(?:обслуж|осмотр|проверк|контрол|ремонт|замен|наладк)\\p{L}*\\s*(\\d)")
                .matcher(s);
        return m.find() ? base + m.group(1) : base;
    }

    /** «ТО» / «т/о» отдельным словом — сокращение технического обслуживания. */
    private static final java.util.regex.Pattern ABBREVIATED_SERVICE =
            java.util.regex.Pattern.compile("(?<![\\p{L}\\d])т\\s*/?\\s*о(?![\\p{L}\\d])");

    /** Категория операции или null, если формулировка не типовая. */
    private static String category(String s) {
        if (s.contains("осмотр")) return "осмотр";
        if (s.contains("замен")) return "замена";
        if (s.contains("ремонт")) return "ремонт";
        // «обслуж» проверяем раньше «проверк»/«контрол»: «Техническое обслуживание …,
        // проверка АКБ» — это ТО, а не отдельная проверка (иначе одна и та же расценка
        // попадает в две категории и строка дублируется)
        if (s.contains("обслуж")) return "то";
        // сокращение из эталонов: «ТО адресного релейного модуля», «Т/О прибора».
        // Без него такая работа не приравнивалась к «Техническому обслуживанию»,
        // и расценка из эталона не находилась для того же оборудования.
        if (ABBREVIATED_SERVICE.matcher(s).find()) return "то";
        if (s.contains("контрол")) return "контроль";
        if (s.contains("проверк")) return "проверка";
        if (s.contains("наладк")) return "наладка";
        return null;
    }

    /** Решения для оборудования (по уникальному id). */
    public List<EstimateRateDecision> lookup(Long uniqueEquipmentId) {
        return uniqueEquipmentId == null ? List.of()
                : repository.findByUniqueEquipmentIdOrderByOperationKey(uniqueEquipmentId);
    }

    /**
     * Решение эталона: расценка + периодичность + обоснование периодичности
     * (ПКМ/паспорт/ГОСТ) — оно переносится в новую смету как есть.
     */
    public record EtalonRate(String rateCode, String periodicity, BigDecimal perYear, String justification) {}

    /**
     * Запись эталона: наименование/операция оборудования → решение (для нечёткого матча).
     * system — из какой инженерной системы взята запись: при выборе вручную инженеру
     * важно видеть, что расценка пришла из эталона другой системы.
     */
    public record EtalonEntry(String name, String operationKey, String system, EtalonRate rate) {}

    /** Операция эталона: имя мероприятия, категория, расценка+периодичность. */
    public record EtalonOp(String operationName, String operationKey, EtalonRate rate) {}

    /** Тип оборудования эталона: наименование/модель + все его операции (для ИИ-сопоставления). */
    public record EtalonType(String nameKey, String name, String model, List<EtalonOp> ops) {}

    /**
     * Данные эталона по системе:
     *  - examples — few-shot примеры «оборудование → шифр» для ИИ;
     *  - byRateCode — «шифр → решение» (если ИИ выбрал эталонный шифр, берём и периодичность);
     *  - byName — «каноническое наименование → все операции эталона»: если оборудование
     *    есть в эталоне по имени, берём ВСЕ его операции (напр. осмотр + ТО), а не одну;
     *  - entries — все записи эталона системы (для нечёткого совпадения по словам имени).
     */
    public record SystemEtalon(List<ru.techdocs.normative.NormativeAiMatchService.Example> examples,
                               Map<String, EtalonRate> byRateCode, Map<String, List<EtalonOp>> byName,
                               List<EtalonEntry> entries, List<EtalonType> types) {}

    /** Каноническое наименование оборудования для сопоставления по типу. */
    public static String nameKey(String name) {
        return name == null ? "" : name.toLowerCase().replace('ё', 'е').replaceAll("\\s+", " ").strip();
    }

    public SystemEtalon systemEtalon(String system, int limit) {
        String canonical = ru.techdocs.common.SystemNormalizer.canonical(system);
        if (canonical == null) return new SystemEtalon(List.of(), Map.of(), Map.of(), List.of(), List.of());
        return buildEtalon(uniqueEquipmentService.bySystemType(canonical), limit);
    }

    /**
     * Эталон по ВСЕМ системам — запасной слой: одно и то же оборудование (напр. источник
     * питания) встречается и в АПС, и в СОУЭ, но в эталон попало только под одной системой.
     * Используется, когда в эталоне своей системы совпадения нет.
     */
    public SystemEtalon globalEtalon(int limit) {
        return buildEtalon(uniqueEquipmentService.all(), limit);
    }

    private SystemEtalon buildEtalon(List<UniqueEquipment> equipment, int limit) {
        List<ru.techdocs.normative.NormativeAiMatchService.Example> examples = new ArrayList<>();
        Map<String, EtalonRate> byRateCode = new HashMap<>();
        Map<String, List<EtalonOp>> byName = new HashMap<>();
        List<EtalonEntry> entries = new ArrayList<>();
        // тип — на каждую запись эталона (со своей моделью): две записи «Блок питания»
        // с разными моделями (БИРП и ИВЭПР) должны остаться разными типами, иначе
        // сопоставление по модели теряет одну из них
        List<EtalonType> types = new ArrayList<>();
        for (UniqueEquipment ue : equipment) {
            String name = ue.getName() == null ? "" : ue.getName();
            String descr = ue.getModel() == null || ue.getModel().isBlank() ? name : name + " " + ue.getModel();
            List<EtalonOp> ueOps = new ArrayList<>();
            for (EstimateRateDecision d : repository.findByUniqueEquipmentIdOrderByOperationKey(ue.getId())) {
                if (d.getRateCode() == null || d.getRateCode().isBlank()) continue;
                EtalonRate er = new EtalonRate(d.getRateCode().strip(), d.getPeriodicity(),
                        d.getPerYear(), d.getJustification());
                byRateCode.putIfAbsent(er.rateCode(), er);
                // категорию считаем заново из названия работы, а не берём сохранённую:
                // старые решения записаны прежними правилами (напр. «ТО …» до того,
                // как сокращение стали распознавать), и перезагружать эталон не нужно
                entries.add(new EtalonEntry(name, operationKey(d.getOperationName()),
                        ue.getSystemType(), er));
                // одна расценка = одна работа: дедуп и по категории операции, и по шифру
                // (в эталоне ТО могло попасть в разные категории — «ТО» и «ТО, проверка АКБ»)
                addOp(ueOps, d, er);
                addOp(byName.computeIfAbsent(nameKey(name), k -> new ArrayList<>()), d, er);
                if (examples.size() < limit) {
                    examples.add(new ru.techdocs.normative.NormativeAiMatchService.Example(descr.strip(), d.getRateCode()));
                }
            }
            if (!ueOps.isEmpty()) types.add(new EtalonType(nameKey(name), name, ue.getModel(), ueOps));
        }
        return new SystemEtalon(examples, byRateCode, byName, entries, types);
    }

    /**
     * Добавляет операцию, если такой расценки ещё нет. Дедуп именно по расценке:
     * одна расценка = одна работа. По категории дедуплицировать нельзя — у одного
     * оборудования бывает несколько работ одной категории («ежемесячное» +
     * «полугодовое» ТО), и они должны попасть в смету обе.
     */
    private void addOp(List<EtalonOp> ops, EstimateRateDecision d, EtalonRate er) {
        if (ops.stream().anyMatch(o -> o.rate().rateCode().equals(er.rateCode()))) return;
        ops.add(new EtalonOp(d.getOperationName(), operationKey(d.getOperationName()), er));
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
     * (оборудование, категория операции, расценка): эталон повторяет одно и то же
     * оборудование на многих строках, но у одной категории бывает НЕСКОЛЬКО работ с
     * разными расценками — «ежемесячное» + «полугодовое» ТО. Их нельзя схлопывать,
     * иначе в смету попадёт половина пары.
     * <p>
     * Для «В эталон» (APPROVED) правка инженера должна заменять прежний выбор, поэтому
     * решения той же категории с другими расценками удаляются.
     */
    @Transactional
    public List<EstimateRateDecision> saveAll(List<DecisionData> data, String source) {
        // сворачиваем к одному решению на (оборудование+система, операция, расценка)
        Map<String, DecisionData> deduped = new LinkedHashMap<>();
        for (DecisionData d : data) {
            if (d.rateCode() == null || d.rateCode().isBlank()) continue; // без расценки — не эталон
            String key = UniqueEquipmentService.normKey(d.equipmentName(), d.model(), d.manufacturer(), d.system())
                    + "|" + operationKey(d.operationName()) + "|" + d.rateCode().strip();
            deduped.put(key, d);
        }

        Map<String, UniqueEquipment> equipmentCache = new HashMap<>();
        List<EstimateRateDecision> result = new ArrayList<>();
        // какие (оборудование, категория) затронуты — для замены при «В эталон»
        Map<String, java.util.Set<String>> approvedRates = new HashMap<>();
        for (DecisionData d : deduped.values()) {
            String normKey = UniqueEquipmentService.normKey(d.equipmentName(), d.model(), d.manufacturer(), d.system());
            UniqueEquipment ue = equipmentCache.computeIfAbsent(normKey,
                    k -> uniqueEquipmentService.resolve(d.equipmentName(), d.model(), d.manufacturer(), d.system()));
            String opKey = operationKey(d.operationName());
            String rateCode = d.rateCode().strip();
            if (EstimateRateDecision.SOURCE_APPROVED.equals(source)) {
                approvedRates.computeIfAbsent(ue.getId() + "|" + opKey, k -> new java.util.HashSet<>()).add(rateCode);
            }
            EstimateRateDecision decision = repository
                    .findByUniqueEquipmentIdAndOperationKeyAndRateCode(ue.getId(), opKey, rateCode)
                    .orElseGet(EstimateRateDecision::new);
            decision.setUniqueEquipmentId(ue.getId());
            decision.setOperationKey(opKey);
            decision.setOperationName(d.operationName());
            decision.setRateCode(d.rateCode().strip());
            decision.setRateName(d.rateName());
            decision.setPeriodicity(d.periodicity());
            decision.setJustification(d.justification());
            decision.setPerYear(d.perYear());
            decision.setCorrection(d.correction());
            decision.setSource(source);
            decision.setUpdatedAt(Instant.now());
            result.add(repository.save(decision));
        }

        // «В эталон»: выбор инженера заменяет прежние расценки той же категории
        for (Map.Entry<String, java.util.Set<String>> e : approvedRates.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            Long equipmentId = Long.valueOf(parts[0]);
            for (EstimateRateDecision stale : repository
                    .findByUniqueEquipmentIdAndOperationKey(equipmentId, parts[1])) {
                if (!e.getValue().contains(stale.getRateCode())) repository.delete(stale);
            }
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
                    row.getRateName(), row.getPeriodicity(), row.getOpsPerYear(), row.getCorrection(),
                    row.getJustification()));
        }
        return saveAll(data, EstimateRateDecision.SOURCE_APPROVED).size();
    }

    public List<EstimateRateDecision> all() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    /** Массовое удаление решений; возвращает число фактически удалённых. */
    @Transactional
    public int deleteAll(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        List<EstimateRateDecision> existing = repository.findAllById(ids);
        repository.deleteAll(existing);
        return existing.size();
    }
}
