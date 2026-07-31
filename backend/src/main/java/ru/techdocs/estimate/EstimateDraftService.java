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
    private final EtalonTypeMatchService typeMatchService;
    private final NormativeRateRepository rateRepository;
    private final ru.techdocs.object.FacilityRepository facilityRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final int EXAMPLE_LIMIT = 20;   // сколько эталонных примеров подкладывать ИИ
    private static final double MODEL_MATCH_THRESHOLD = 0.75;  // схожесть моделей для «то же изделие»
    /** Комплексные испытания систем противопожарной защиты — раз в год, измеритель 1000 м². */
    private static final String FIRE_TEST_RATE = "22-2203-117-1/1";

    public record DraftResult(int created, int skipped, boolean aiUsed) {}

    /** Выбор расценки для кэша консистентности «по типу» в пределах одной сметы. */
    private record RatePick(String rateCode, String source, boolean needsReview,
                            String periodicityText, BigDecimal perYear, String suggestions) {}

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
        // ИИ-сопоставление типа с эталоном: один запрос на (система|наименование)
        Map<String, EstimateDecisionService.EtalonType> typeMatchCache = new HashMap<>();
        // эталон по всем системам — строится лениво, один раз на сборку
        EstimateDecisionService.SystemEtalon[] globalEtalonHolder = new EstimateDecisionService.SystemEtalon[1];
        // системы, попавшие в смету — для общесистемных работ (комплексные испытания СПЗ)
        Set<String> systemsInEstimate = new java.util.LinkedHashSet<>();
        int created = 0, skipped = 0;
        for (Equipment eq : equipment) {
            if (existing.contains(eq.getId())) { skipped++; continue; }
            // аккумуляторы в смету не вносим
            if (isExcluded(eq.getName())) { skipped++; continue; }

            String systemType = systemName(eq.getEngineeringSystemId());
            systemsInEstimate.add(systemType);

            // 1) память решений (эталоны): то же оборудование уже считали — берём готовое,
            //    без ИИ, консистентно. По строке на каждое запомненное решение.
            Long uniqueId = eq.getUniqueEquipmentId() != null ? eq.getUniqueEquipmentId()
                    : decisionService.resolveUniqueId(eq.getName(), eq.getModel(), eq.getManufacturer(), systemType);
            List<EstimateRateDecision> decisions = decisionService.lookup(uniqueId);
            if (!decisions.isEmpty()) {
                Set<String> seenRates = new HashSet<>();
                for (EstimateRateDecision d : decisions) {
                    // одна расценка = одна строка (категории могли разойтись: «ТО» и «ТО, проверка АКБ»)
                    if (d.getRateCode() != null && !seenRates.add(d.getRateCode())) continue;
                    addRowFromDecision(estimateId, eq, systemType, d);
                    // из памяти пополняем кэш «по типу»: новые модели того же типа возьмут ту же расценку
                    consistency.putIfAbsent(typeKey(systemType, eq.getName(), d.getOperationName()),
                            new RatePick(d.getRateCode(), "LEARNED", false, d.getPeriodicity(), d.getPerYear(), null));
                    created++;
                }
                continue;
            }

            EstimateDecisionService.SystemEtalon etalon = etalonCache.computeIfAbsent(
                    systemType, s -> decisionService.systemEtalon(s, EXAMPLE_LIMIT));

            // 2) эталон по наименованию (совпадение по значимым словам, с учётом суффиксов
            //    моделей): если оборудование есть в эталоне — берём ВСЕ его операции
            //    (напр. осмотр + ТО), детерминированно, каждую отдельной строкой.
            List<EstimateDecisionService.EtalonOp> etalonOps = etalonOpsForType(etalon, eq.getName(), eq.getModel());
            String etalonSource = "ETALON_TYPE";

            // 2б) совпадение по МОДЕЛИ (детерминированно): «ИВЭПР 12/2 RS-R3 2x7 БР» на объекте
            //     и «ИВЭПР 12/2 RSR3 2x7-Р БР» в эталоне — одно и то же, хотя названия разные
            //     («Источник вторичного электропитания» vs «Блок питания»).
            if (etalonOps.isEmpty()) {
                List<EstimateDecisionService.EtalonOp> byModel = etalonOpsByModel(etalon, eq.getModel());
                if (!byModel.isEmpty()) etalonOps = byModel;
            }

            // 2в) эталон ДРУГИХ систем: одно и то же оборудование (источник питания,
            //     коммутатор) есть и в АПС, и в СОУЭ, но в эталон попало под одной
            //     системой. Не заставляем инженера выбирать — берём готовое решение.
            if (etalonOps.isEmpty()) {
                EstimateDecisionService.SystemEtalon all = globalEtalon(globalEtalonHolder);
                List<EstimateDecisionService.EtalonOp> cross = etalonOpsForType(all, eq.getName(), eq.getModel());
                if (cross.isEmpty()) cross = etalonOpsByModel(all, eq.getModel());
                if (!cross.isEmpty()) {
                    etalonOps = cross;
                    etalonSource = "ETALON_XSYS";
                }
            }

            if (etalonOps.isEmpty() && !etalon.types().isEmpty()) {
                // 2б) синонимы: в эталоне «Блок питания», на объекте «Источник вторичного
                //     электропитания» — общих слов нет, но это одно и то же. Тип определяет ИИ,
                //     расценки и периодичность всех операций берутся из эталона детерминированно.
                String tk = (systemType == null ? "" : systemType) + "|" + EstimateDecisionService.nameKey(eq.getName());
                if (!typeMatchCache.containsKey(tk)) {      // один запрос на наименование
                    typeMatchCache.put(tk, typeMatchService.match(eq, etalon.types()));
                }
                EstimateDecisionService.EtalonType matched = typeMatchCache.get(tk);
                if (matched != null) {
                    etalonOps = matched.ops();
                    etalonSource = "AI_TYPE";
                }
            }
            if (!etalonOps.isEmpty()) {
                for (EstimateDecisionService.EtalonOp eop : etalonOps) {
                    addRowFromEtalonOp(estimateId, eq, systemType, eop, etalonSource);
                    created++;
                }
                continue;
            }

            // 3) паспорт/ПКМ + ИИ-подбор расценки (с few-shot из эталона и консистентностью по типу)
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
        created += addSystemWideRows(estimateId, estimate, systemsInEstimate);

        log.info("Черновик сметы {}: создано {} строк, пропущено {} (ИИ: {})",
                estimateId, created, skipped, aiAvailable);
        return new DraftResult(created, skipped, aiAvailable);
    }


    /**
     * Общесистемные работы, не привязанные к оборудованию. Для АПС это комплексные
     * испытания систем противопожарной защиты (раз в год, измеритель 1000 м²):
     * количество берётся из площади объекта. Площадь не указана — строка добавляется
     * с пустым количеством и пометкой «на проверку», чтобы её не потеряли.
     */
    private int addSystemWideRows(Long estimateId, Estimate estimate, Set<String> systems) {
        boolean hasFireAlarm = systems.stream()
                .anyMatch(s -> "апс".equals(ru.techdocs.common.SystemNormalizer.canonical(s)));
        if (!hasFireAlarm) return 0;

        NormativeRate rate = catalogRate(FIRE_TEST_RATE);
        if (rate == null) return 0;                       // расценки нет в каталоге — нечего добавлять
        for (EstimateRow row : rowRepository.findByEstimateIdOrderByPosition(estimateId)) {
            if (FIRE_TEST_RATE.equals(row.getRateCode())) return 0;   // уже добавлена
        }

        String system = systems.stream()
                .filter(s -> "апс".equals(ru.techdocs.common.SystemNormalizer.canonical(s)))
                .findFirst().orElse(null);
        BigDecimal area = facilityRepository.findById(estimate.getFacilityId())
                .map(ru.techdocs.object.Facility::getAreaSqm).orElse(null);
        boolean noArea = area == null || area.signum() <= 0;

        EstimateService.RowInput input = new EstimateService.RowInput(
                system, null, "Системы противопожарной защиты объекта", null, null,
                "Комплексные испытания систем пожарной сигнализации, оповещения и управления эвакуацией",
                FIRE_TEST_RATE, null, "раз в год",
                "Технический регламент эксплуатации здания",
                BigDecimal.ONE, noArea ? null : area,
                null, null, null, null, null,
                null, null,
                noArea, "SYSTEM",
                null,
                noArea ? "⚠ НА ПРОВЕРКУ: укажите площадь объекта — количество для расценки с измерителем в м² берётся из неё."
                       : "Общесистемная работа: комплексные испытания СПЗ, количество — площадь объекта (измеритель расценки в м²).");
        estimateService.addRow(estimateId, input);
        return 1;
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
                    note,
                    "Расценка согласована с оборудованием того же типа в этой смете.",
                    cached.needsReview(), cached.source(), cached.suggestions());
            return;
        }

        // (точное совпадение по наименованию обработано на уровне generate — п.2)

        // подбор расценки под конкретную операцию (с few-shot примерами из эталона)
        String query = op == null ? describe(eq) : describe(eq) + " " + op.operationName();
        var match = aiMatchService.match(query, etalon.examples(), systemType);

        // Похожее (не точное) наименование в эталоне → НЕ решаем за инженера: строим
        // список вариантов (эталон + топ ИИ) и даём выбрать. Первый вариант ИИ — его
        // собственный выбор, дальше аналоги.
        List<EstimateDecisionService.EtalonEntry> fuzzy = fuzzyEtalon(etalon.entries(), eq.getName(),
                EstimateDecisionService.operationKey(operationName));
        if (!fuzzy.isEmpty()) {
            String suggestionsJson = buildSuggestions(fuzzy, match);
            // по умолчанию — выбор ИИ (если есть), иначе первый эталонный вариант
            String defCode; String defSource;
            NormativeRate defRate;
            if (!match.matches().isEmpty()) {
                defRate = match.matches().get(0).rate();
                defCode = defRate.getCode();
                defSource = "CHOICE";
            } else {
                defRate = catalogRate(fuzzy.get(0).rate().rateCode());
                defCode = fuzzy.get(0).rate().rateCode();
                defSource = "CHOICE";
            }
            Per defPer = periodicity(op, eq.getName(), defRate);
            BigDecimal defPerYear = defPer.perYear();
            String defPeriodicity = defPer.text();
            consistency.put(tkey, new RatePick(defCode, defSource, true, defPeriodicity, defPerYear, suggestionsJson));
            addRow(estimateId, eq, systemType, operationName, defCode, defPeriodicity, defPerYear,
                    note,
                    "Похожее оборудование есть в эталоне — выберите расценку из вариантов.",
                    true, defSource, suggestionsJson);
            return;
        }
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
            Per per = periodicity(op, eq.getName(), rate);
            perYear = per.perYear();
            periodicityText = per.text();
        }

        consistency.put(tkey, new RatePick(rateCode, source, needsReview, periodicityText, perYear, null));
        addRow(estimateId, eq, systemType, operationName, rateCode, periodicityText, perYear,
                note, matchNote(match, source), needsReview, source, null);
    }

    /** Ленивое построение эталона по всем системам (один раз на сборку). */
    private EstimateDecisionService.SystemEtalon globalEtalon(EstimateDecisionService.SystemEtalon[] holder) {
        if (holder[0] == null) holder[0] = decisionService.globalEtalon(EXAMPLE_LIMIT);
        return holder[0];
    }

    /** Записи эталона системы с похожим (не точным) наименованием — для выбора инженером. */
    private List<EstimateDecisionService.EtalonEntry> fuzzyEtalon(
            List<EstimateDecisionService.EtalonEntry> entries, String name, String opKey) {
        Set<String> objTokens = tokens(name);
        if (objTokens.isEmpty()) return List.of();
        String exact = EstimateDecisionService.nameKey(name);
        record Scored(EstimateDecisionService.EtalonEntry e, int shared) {}
        List<Scored> scored = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        for (EstimateDecisionService.EtalonEntry e : entries) {
            if (!opKey.equals(e.operationKey())) continue;
            if (EstimateDecisionService.nameKey(e.name()).equals(exact)) continue; // точное — уже обработано
            long shared = tokens(e.name()).stream().filter(objTokens::contains).count();
            if (shared == 0) continue;
            scored.add(new Scored(e, (int) shared));
        }
        scored.sort((a, b) -> Integer.compare(b.shared(), a.shared()));
        List<EstimateDecisionService.EtalonEntry> result = new ArrayList<>();
        for (Scored s : scored) {
            if (s.e().rate().rateCode() == null || !seenCodes.add(s.e().rate().rateCode())) continue;
            result.add(s.e());
            if (result.size() >= 2) break;
        }
        return result;
    }

    /** Значимые токены наименования (буквы/цифры, длиной ≥ 4) для нечёткого сравнения. */
    private Set<String> tokens(String name) {
        Set<String> set = new HashSet<>();
        for (String t : EstimateDecisionService.nameKey(name).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 4) set.add(t);
        }
        return set;
    }

    /** Варианты выбора (JSON): сначала из эталона (похожие), затем топ предложений ИИ. */
    private String buildSuggestions(List<EstimateDecisionService.EtalonEntry> fuzzy,
                                    NormativeAiMatchService.MatchResult match) {
        List<Map<String, Object>> options = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (EstimateDecisionService.EtalonEntry e : fuzzy) {
            String code = e.rate().rateCode();
            if (code == null || !codes.add(code)) continue;
            NormativeRate r = catalogRate(code);
            options.add(option("ETALON", code, r == null ? null : r.getName(),
                    e.rate().periodicity(), "из эталона: " + e.name()));
        }
        int aiShown = 0;
        for (NormativeAiMatchService.Match m : match.matches()) {
            if (m.rate() == null || !codes.add(m.rate().getCode())) continue;
            options.add(option("AI", m.rate().getCode(), m.rate().getName(), null,
                    aiShown == 0 ? "выбор ИИ: " + safe(m.reason()) : "аналог: " + safe(m.reason())));
            if (++aiShown >= 3) break;
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (Exception ex) {
            log.warn("Не удалось сериализовать варианты расценки: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> option(String source, String rateCode, String rateName,
                                       String periodicity, String note) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("source", source);
        m.put("rateCode", rateCode);
        m.put("rateName", rateName);
        m.put("periodicity", periodicity);
        m.put("note", note);
        return m;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    /** Что не так со строкой (иначе null): такие строки дают в смете ноль. */
    private String rowProblem(String rateCode, String periodicity, BigDecimal perYear, BigDecimal qty) {
        if (rateCode == null || rateCode.isBlank()) return "не подобрана расценка.";
        if (periodicity == null || periodicity.isBlank()) return "не определена периодичность.";
        if (perYear == null || perYear.signum() <= 0) return "не определено количество операций в год.";
        if (qty == null || qty.signum() <= 0) return "нулевое количество оборудования.";
        return null;
    }

    /**
     * Общая сборка строки черновика. Два обоснования разделены: {@code justification}
     * (колонка I) — чем установлена периодичность (ПКМ, паспорт, ГОСТ, регламент), это
     * идёт заказчику; {@code matchNote} (колонка AS) — служебное, как приложение
     * подобрало расценку. Перед записью — валидация: строка без расценки, без
     * периодичности или с нулевым числом операций/выполнений считается нерешённой
     * и помечается «на проверку», а не попадает в смету молча нулевой.
     */
    private void addRow(Long estimateId, Equipment eq, String systemType, String operationName,
                        String rateCode, String periodicityText, BigDecimal perYear,
                        String justification, String matchNote,
                        boolean needsReview, String source, String suggestions) {
        String problem = rowProblem(rateCode, periodicityText, perYear, eq.getQuantity());
        if (problem != null) {
            needsReview = true;
            matchNote = "⚠ НА ПРОВЕРКУ: " + problem + (matchNote == null ? "" : " " + matchNote);
        }
        EstimateService.RowInput input = new EstimateService.RowInput(
                systemType, eq.getId(), eq.getName(), eq.getModel(), eq.getManufacturer(),
                operationName,
                rateCode,
                null,
                ru.techdocs.common.Periodicity.label(periodicityText, perYear),
                justification,
                perYear,
                eq.getQuantity(),
                null, null, null, null, null,
                null, null,
                needsReview, source, suggestions, matchNote);
        estimateService.addRow(estimateId, input);
    }

    private NormativeRate catalogRate(String code) {
        return rateRepository.findFirstByCodeOrderById(code).orElse(null);
    }

    /** Оборудование, которое не вносим в смету (аккумуляторы). */
    private boolean isExcluded(String name) {
        String n = EstimateDecisionService.nameKey(name);
        if (n.contains("аккумулятор")) return true;
        for (String token : n.split("[^\\p{L}\\p{N}]+")) {
            if (token.equals("акб")) return true;
        }
        return false;
    }

    /**
     * Все операции эталона для типа оборудования: эталонная запись подходит, если её
     * значимые слова — подмножество слов объекта или наоборот (учитывает суффиксы моделей,
     * напр. «Источник вторичного электропитания (для STR-1AP)» ⊇ «Источник вторичного
     * электропитания»). Операции разных подходящих записей объединяются (дедуп по категории).
     */
    private List<EstimateDecisionService.EtalonOp> etalonOpsForType(
            EstimateDecisionService.SystemEtalon etalon, String objName, String objModel) {
        Set<String> objT = tokens(objName);
        if (objT.isEmpty()) return List.of();

        // типы эталона, подходящие по наименованию
        List<EstimateDecisionService.EtalonType> byName = new ArrayList<>();
        for (EstimateDecisionService.EtalonType t : etalon.types()) {
            Set<String> etT = tokens(t.nameKey());
            if (etT.isEmpty()) continue;
            if (objT.containsAll(etT) || etT.containsAll(objT)) byName.add(t);
        }
        if (byName.isEmpty()) return List.of();

        // Одно наименование на несколько изделий («Адресный релейный модуль» — РМ-1 и
        // РМ-4 с разными расценками): различаем по модели. Если модель не совпала ни с
        // одним — не берём молча первое, отдаём решение инженеру (строка «выбрать»).
        if (byName.size() > 1 && differentRates(byName)) {
            EstimateDecisionService.EtalonType best = null;
            double bestScore = 0;
            String objKey = modelKey(objModel);
            if (objKey.length() >= 2) {
                for (EstimateDecisionService.EtalonType t : byName) {
                    String etKey = modelKey(t.model());
                    if (etKey.length() < 2) continue;
                    double score = similarity(objKey, etKey);
                    if (score > bestScore) { bestScore = score; best = t; }
                }
            }
            return best != null && bestScore >= MODEL_MATCH_THRESHOLD ? best.ops() : List.of();
        }

        List<EstimateDecisionService.EtalonOp> result = new ArrayList<>();
        Set<String> seenRate = new HashSet<>();
        for (EstimateDecisionService.EtalonType t : byName) {
            for (EstimateDecisionService.EtalonOp op : t.ops()) {
                // одна расценка = одна работа; несколько работ одной категории
                // («ежемесячное» + «полугодовое» ТО) сохраняем обе
                if (seenRate.add(op.rate().rateCode())) result.add(op);
            }
        }
        return result;
    }

    /** У подходящих по имени типов эталона разные наборы расценок — значит это разные изделия. */
    private boolean differentRates(List<EstimateDecisionService.EtalonType> types) {
        Set<String> first = rateCodes(types.get(0));
        for (int i = 1; i < types.size(); i++) {
            if (!first.equals(rateCodes(types.get(i)))) return true;
        }
        return false;
    }

    private Set<String> rateCodes(EstimateDecisionService.EtalonType t) {
        Set<String> codes = new HashSet<>();
        for (EstimateDecisionService.EtalonOp op : t.ops()) codes.add(op.rate().rateCode());
        return codes;
    }

    /**
     * Операции эталона по совпадению МОДЕЛИ. Модели одного изделия пишут по-разному
     * («ИВЭПР 12/2 RS-R3 2x7 БР» / «ИВЭПР 12/2 RSR3 2x7-Р БР», латинская x и кириллическая х),
     * поэтому сравниваем нормализованные строки по схожести. Детерминированно, без ИИ.
     */
    private List<EstimateDecisionService.EtalonOp> etalonOpsByModel(
            EstimateDecisionService.SystemEtalon etalon, String model) {
        String objKey = modelKey(model);
        if (objKey.length() < 4) return List.of();
        EstimateDecisionService.EtalonType best = null;
        double bestScore = 0;
        for (EstimateDecisionService.EtalonType t : etalon.types()) {
            String etKey = modelKey(t.model());
            if (etKey.length() < 4) continue;
            double score = similarity(objKey, etKey);
            if (score > bestScore) { bestScore = score; best = t; }
        }
        return bestScore >= MODEL_MATCH_THRESHOLD && best != null ? best.ops() : List.of();
    }

    /** Служебные части модели, не различающие изделия: маркер протокола Рубеж. */
    private static final Set<String> MODEL_NOISE = Set.of("прот", "r3", "р3");

    /**
     * Нормализация модели: регистр, ё→е, похожие кириллические буквы → латиница,
     * только буквы/цифры. Маркер протокола отбрасывается — «РМ-4-R3» на объекте и
     * «РМ-4 прот. R3» в эталоне это одно изделие, а «РМ-1 прот. R3» — другое.
     */
    private String modelKey(String model) {
        if (model == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String token : model.toLowerCase().replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (token.isBlank() || MODEL_NOISE.contains(token)) continue;
            for (char c : token.toCharArray()) {
                sb.append(switch (c) {     // визуально одинаковые кириллица/латиница
                    case 'х' -> 'x'; case 'а' -> 'a'; case 'е' -> 'e'; case 'о' -> 'o';
                    case 'р' -> 'p'; case 'с' -> 'c'; case 'у' -> 'y'; case 'к' -> 'k';
                    default -> c;
                });
            }
        }
        return sb.toString();
    }

    /** Схожесть строк 0..1 по расстоянию Левенштейна. */
    private double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 0;
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    /** Строка из операции эталона — расценка/периодичность из эталона (ИИ в цифрах не участвует). */
    private void addRowFromEtalonOp(Long estimateId, Equipment eq, String systemType,
                                    EstimateDecisionService.EtalonOp eop, String source) {
        NormativeRate rate = catalogRate(eop.rate().rateCode());
        BigDecimal perYear = eop.rate().perYear() != null ? eop.rate().perYear()
                : maintenanceResolver.perYearFromRate(rate == null ? null : rate.getName());
        String periodicityText = eop.rate().periodicity() != null ? eop.rate().periodicity()
                : maintenanceResolver.label(perYear);
        String operationName = eop.operationName() != null ? eop.operationName() : operationName(eq);
        String matchNote = switch (source) {
            case "AI_TYPE" -> "Расценка и периодичность из эталона (тип определён ИИ как то же оборудование).";
            case "ETALON_XSYS" -> "Расценка и периодичность из эталона другой системы "
                    + "(то же оборудование уже считали там).";
            default -> "Расценка и периодичность из эталона (то же наименование в этой системе).";
        };
        // обоснование периодичности переносится из эталона как есть (ПКМ / паспорт / ГОСТ)
        String justification = eop.rate().justification() != null ? eop.rate().justification()
                : "периодичность по эталонной смете";
        addRow(estimateId, eq, systemType, operationName, eop.rate().rateCode(), periodicityText, perYear,
                justification, matchNote, false, source, null);
    }

    /** Периодичность строки в единый год выполнений/текст. */
    private record Per(BigDecimal perYear, String text) {}

    /**
     * Периодичность операции: паспорт (если есть) → дефолт по типу оборудования
     * (напр. извещатель без паспорта → раз в 6 мес.) → ПКМ/наименование расценки.
     */
    private Per periodicity(EquipmentMaintenanceResolver.Planned op, String name, NormativeRate rate) {
        boolean passport = op != null && EquipmentMaintenanceResolver.SOURCE_PASSPORT.equals(op.source());
        if (passport && op.perYear() != null) {
            return new Per(op.perYear(), op.periodicityText() != null ? op.periodicityText()
                    : maintenanceResolver.label(op.perYear()));
        }
        Per td = typeDefault(name);
        if (td != null) return td;
        BigDecimal py = op != null && op.perYear() != null ? op.perYear()
                : maintenanceResolver.perYearFromRate(rate == null ? null : rate.getName());
        String txt = op != null && op.periodicityText() != null ? op.periodicityText()
                : maintenanceResolver.label(py);
        return new Per(py, txt);
    }

    /** Периодичность по умолчанию для типа оборудования (без паспорта). */
    private Per typeDefault(String name) {
        String n = EstimateDecisionService.nameKey(name);
        if (n.contains("извещател")) return new Per(new BigDecimal("2"), "раз в 6 мес.");
        return null;
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
                ru.techdocs.common.Periodicity.label(d.getPeriodicity(), d.getPerYear()),
                // обоснование периодичности — из эталона как есть (ПКМ / паспорт / ГОСТ)
                d.getJustification() != null ? d.getJustification() : "периодичность по эталонной смете",
                d.getPerYear(),
                eq.getQuantity(),
                null, null, null, null, null,
                d.getCorrection(), null,
                false, "LEARNED", null,
                "Расценка и периодичность " + note + " (память эталонных решений).");
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

    /** Служебное обоснование расценки (колонка AS) — как именно она подобрана. */
    private String matchNote(NormativeAiMatchService.MatchResult match, String source) {
        return switch (source) {
            case "AI", "AI_ETALON" -> {
                String reason = match.matches().isEmpty() ? null : match.matches().get(0).reason();
                String prefix = "AI_ETALON".equals(source) ? "Расценка (ИИ, по эталону)" : "Расценка (ИИ)";
                yield reason != null ? prefix + ": " + reason + "." : prefix + ".";
            }
            case "AI_FAILED" -> "⚠ НА ПРОВЕРКУ: ИИ не подобрал расценку — выберите вручную.";
            case "CATALOG" -> "⚠ НА ПРОВЕРКУ: ИИ выключен, расценка — верхний результат поиска по каталогу.";
            default -> null;
        };
    }

    private String systemName(Long systemId) {
        if (systemId == null) return null;
        return systemRepository.findById(systemId).map(EngineeringSystem::getName).orElse(null);
    }
}
