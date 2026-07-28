package ru.techdocs;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.ai.AiClient;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;
import ru.techdocs.estimate.EstimateRateDecision;
import ru.techdocs.estimate.EstimateRateDecisionRepository;
import ru.techdocs.normative.NormativeRate;
import ru.techdocs.normative.NormativeRateRepository;
import ru.techdocs.normative.NormativeSourcebook;
import ru.techdocs.normative.NormativeSourcebookRepository;
import ru.techdocs.object.Facility;
import ru.techdocs.object.FacilityRepository;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** ИИ выбрал расценку из эталона → источник AI_ETALON и периодичность из эталона. */
@Transactional
class EstimateDraftEtalonTest extends IntegrationTestBase {

    @MockBean AiClient aiClient;

    @Autowired NormativeSourcebookRepository sourcebookRepository;
    @Autowired NormativeRateRepository rateRepository;
    @Autowired EngineeringSystemRepository systemRepository;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired UniqueEquipmentRepository uniqueRepository;
    @Autowired EstimateRateDecisionRepository decisionRepository;
    @Autowired FacilityRepository facilityRepository;

    @Test
    void aiPickFromEtalonIsLabeledAndTakesEtalonPeriodicity() throws Exception {
        // каталог: одна расценка
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("Сборник 21");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);
        NormativeRate rate = new NormativeRate();
        rate.setSourcebookId(book.getId());
        rate.setCode("21-СКУД-1");
        rate.setName("Техническое обслуживание считывателя СКУД");
        rate.setUnit("1 шт.");
        rate.setLaborCost(new BigDecimal("100.00"));
        rateRepository.saveAndFlush(rate);

        // эталон системы СКУД: оборудование БЕЗ общих слов с объектом «Считыватель»
        // (чтобы не сработал ни точный, ни нечёткий матч по имени) с расценкой 21-СКУД-1 —
        // проверяем путь ИИ: ИИ выберет эталонный шифр → AI_ETALON + периодичность из эталона
        UniqueEquipment ue = new UniqueEquipment();
        ue.setNormKey("контроллер доступа|old-model||скуд");
        ue.setEquipKey("контроллер доступа|old-model|");
        ue.setSystemType("скуд");
        ue.setName("Контроллер доступа");
        ue.setModel("OLD-MODEL");
        ue = uniqueRepository.saveAndFlush(ue);
        EstimateRateDecision d = new EstimateRateDecision();
        d.setUniqueEquipmentId(ue.getId());
        d.setOperationKey("то");
        d.setOperationName("Техническое обслуживание");
        d.setRateCode("21-СКУД-1");
        d.setPeriodicity("раз в год");
        d.setPerYear(BigDecimal.ONE);
        d.setSource(EstimateRateDecision.SOURCE_REFERENCE);
        decisionRepository.saveAndFlush(d);

        // объект с новой моделью считывателя в СКУД (точного совпадения с эталоном нет → путь ИИ)
        Facility f = new Facility();
        f.setName("Объект");
        f = facilityRepository.saveAndFlush(f);
        EngineeringSystem s = new EngineeringSystem();
        s.setFacilityId(f.getId());
        s.setName("СКУД");
        long sys = systemRepository.saveAndFlush(s).getId();
        Equipment eq = new Equipment();
        eq.setFacilityId(f.getId());
        eq.setEngineeringSystemId(sys);
        eq.setName("Считыватель");
        eq.setModel("NEW-MODEL");
        eq.setQuantity(new BigDecimal("3"));
        equipmentRepository.saveAndFlush(eq);

        // ИИ настроен и выбирает эталонную расценку
        Mockito.when(aiClient.hasMatchModel()).thenReturn(true);
        Mockito.when(aiClient.completeMatch(anyString(), anyString()))
                .thenReturn("[{\"code\":\"21-СКУД-1\",\"reason\":\"как в эталоне\"}]");

        String est = mockMvc.perform(post("/api/estimates?facilityId=" + f.getId()).header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Смета\"}"))
                .andReturn().getResponse().getContentAsString();
        long estId = json.readTree(est).get("id").asLong();
        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys).header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(1));

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode row = view.get("rows").get(0).get("row");
        assertThat(row.get("rateCode").asText()).isEqualTo("21-СКУД-1");
        assertThat(row.get("matchSource").asText()).isEqualTo("AI_ETALON");   // ИИ выбрал эталонную расценку
        assertThat(row.get("needsReview").asBoolean()).isFalse();
        assertThat(row.get("periodicity").asText()).isEqualTo("раз в год");   // периодичность из эталона, не из ПКМ
        assertThat(row.get("opsPerYear").asDouble()).isEqualTo(1.0);
    }

    /** В эталоне есть оборудование с тем же наименованием → берём его расценку без ИИ. */
    @Test
    void etalonByNameIsDeterministicAndSkipsAi() throws Exception {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("Сборник 1");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);
        NormativeRate rate = new NormativeRate();
        rate.setSourcebookId(book.getId());
        rate.setCode("1-МС-1");
        rate.setName("Техническое обслуживание модуля сопряжения");
        rate.setUnit("1 шт.");
        rate.setLaborCost(new BigDecimal("50.00"));
        rateRepository.saveAndFlush(rate);

        // эталон: «Модуль сопряжения» другой модели → расценка 1-МС-1, периодичность «раз в 6 мес.»
        UniqueEquipment ue = new UniqueEquipment();
        ue.setNormKey("модуль сопряжения|mc-old||скуд");
        ue.setEquipKey("модуль сопряжения|mc-old|");
        ue.setSystemType("скуд");
        ue.setName("Модуль сопряжения");
        ue.setModel("MC-OLD");
        ue = uniqueRepository.saveAndFlush(ue);
        EstimateRateDecision d = new EstimateRateDecision();
        d.setUniqueEquipmentId(ue.getId());
        d.setOperationKey("то");
        d.setOperationName("Техническое обслуживание");
        d.setRateCode("1-МС-1");
        d.setPeriodicity("раз в 6 мес.");
        d.setPerYear(new BigDecimal("2"));
        d.setSource(EstimateRateDecision.SOURCE_REFERENCE);
        decisionRepository.saveAndFlush(d);

        Facility f = new Facility();
        f.setName("Объект 2");
        f = facilityRepository.saveAndFlush(f);
        EngineeringSystem s = new EngineeringSystem();
        s.setFacilityId(f.getId());
        s.setName("СКУД");
        long sys = systemRepository.saveAndFlush(s).getId();
        Equipment eq = new Equipment();
        eq.setFacilityId(f.getId());
        eq.setEngineeringSystemId(sys);
        eq.setName("Модуль сопряжения");
        eq.setModel("MC-NEW");
        eq.setQuantity(new BigDecimal("1"));
        equipmentRepository.saveAndFlush(eq);

        // ИИ настроен, но НЕ должен вызываться — совпадение по наименованию детерминированно
        Mockito.when(aiClient.hasMatchModel()).thenReturn(true);

        String est = mockMvc.perform(post("/api/estimates?facilityId=" + f.getId()).header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Смета\"}"))
                .andReturn().getResponse().getContentAsString();
        long estId = json.readTree(est).get("id").asLong();
        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys).header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(1));

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode row = view.get("rows").get(0).get("row");
        assertThat(row.get("rateCode").asText()).isEqualTo("1-МС-1");        // расценка модуля сопряжения, не контроллера
        assertThat(row.get("matchSource").asText()).isEqualTo("ETALON_TYPE");
        assertThat(row.get("periodicity").asText()).isEqualTo("раз в 6 мес.");
        Mockito.verify(aiClient, Mockito.never()).completeMatch(anyString(), anyString());
    }

    /** Похожее (не точное) наименование в эталоне → строка «выбрать» с вариантами эталон + ИИ. */
    @Test
    void fuzzyEtalonNameOffersChoice() throws Exception {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("Сборник 21сч");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);
        for (String[] r : new String[][]{
                {"21-ET-1", "Техническое обслуживание считывателя эталонного"},
                {"21-AI-1", "Техническое обслуживание считывателя аналогичного"}}) {
            NormativeRate rate = new NormativeRate();
            rate.setSourcebookId(book.getId());
            rate.setCode(r[0]);
            rate.setName(r[1]);
            rate.setUnit("1 шт.");
            rate.setLaborCost(new BigDecimal("100.00"));
            rateRepository.saveAndFlush(rate);
        }

        // эталон: «Считыватель настольный USB» → 21-ET-1. Объект — «Считыватель бесконтактный EM»:
        // общее слово «считыватель», но ни одно имя не является подмножеством другого → выбор, не детерминизм
        UniqueEquipment ue = new UniqueEquipment();
        ue.setNormKey("считыватель настольный usb|rd-old||скуд");
        ue.setEquipKey("считыватель настольный usb|rd-old|");
        ue.setSystemType("скуд");
        ue.setName("Считыватель настольный USB");
        ue.setModel("RD-OLD");
        ue = uniqueRepository.saveAndFlush(ue);
        EstimateRateDecision d = new EstimateRateDecision();
        d.setUniqueEquipmentId(ue.getId());
        d.setOperationKey("то");
        d.setOperationName("Техническое обслуживание");
        d.setRateCode("21-ET-1");
        d.setPeriodicity("раз в год");
        d.setPerYear(BigDecimal.ONE);
        d.setSource(EstimateRateDecision.SOURCE_REFERENCE);
        decisionRepository.saveAndFlush(d);

        Facility f = new Facility();
        f.setName("Объект 3");
        f = facilityRepository.saveAndFlush(f);
        EngineeringSystem s = new EngineeringSystem();
        s.setFacilityId(f.getId());
        s.setName("СКУД");
        long sys = systemRepository.saveAndFlush(s).getId();
        Equipment eq = new Equipment();
        eq.setFacilityId(f.getId());
        eq.setEngineeringSystemId(sys);
        eq.setName("Считыватель бесконтактный EM");   // похоже, но не точно как в эталоне
        eq.setModel("RD-NEW");
        eq.setQuantity(new BigDecimal("1"));
        equipmentRepository.saveAndFlush(eq);

        Mockito.when(aiClient.hasMatchModel()).thenReturn(true);
        Mockito.when(aiClient.completeMatch(anyString(), anyString()))
                .thenReturn("[{\"code\":\"21-AI-1\",\"reason\":\"похожий считыватель\"}]");

        String est = mockMvc.perform(post("/api/estimates?facilityId=" + f.getId()).header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Смета\"}"))
                .andReturn().getResponse().getContentAsString();
        long estId = json.readTree(est).get("id").asLong();
        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys).header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(1));

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode row = view.get("rows").get(0).get("row");
        assertThat(row.get("matchSource").asText()).isEqualTo("CHOICE");
        assertThat(row.get("needsReview").asBoolean()).isTrue();
        String suggestions = row.get("suggestions").asText();
        // варианты содержат и эталонную расценку, и предложение ИИ
        assertThat(suggestions).contains("21-ET-1").contains("ETALON").contains("21-AI-1");
    }

    private long facilitySystem(String facilityName) {
        Facility f = new Facility();
        f.setName(facilityName);
        f = facilityRepository.saveAndFlush(f);
        EngineeringSystem s = new EngineeringSystem();
        s.setFacilityId(f.getId());
        s.setName("СКУД");
        return systemRepository.saveAndFlush(s).getId();
    }

    private long estimateFor(long facilityId) throws Exception {
        String est = mockMvc.perform(post("/api/estimates?facilityId=" + facilityId).header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Смета\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(est).get("id").asLong();
    }

    private void equip(long sys, String name, String model) {
        Long facilityId = systemRepository.findById(sys).orElseThrow().getFacilityId();
        Equipment e = new Equipment();
        e.setFacilityId(facilityId);
        e.setEngineeringSystemId(sys);
        e.setName(name);
        e.setModel(model);
        e.setQuantity(new BigDecimal("1"));
        equipmentRepository.saveAndFlush(e);
    }

    /** Аккумуляторы в смету не вносятся. */
    @Test
    void batteriesAreExcluded() throws Exception {
        long sys = facilitySystem("Объект-акб");
        equip(sys, "Аккумулятор", "12В 7Ач");
        equip(sys, "АКБ", "12В 17Ач");
        long estId = estimateFor(estFacility(sys));
        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys).header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(2));
    }

    private long estFacility(long sys) {
        return systemRepository.findById(sys).orElseThrow().getFacilityId();
    }

    /** Извещатель без паспорта → периодичность по умолчанию раз в 6 мес. */
    @Test
    void detectorDefaultsToSemiannual() throws Exception {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("Сборник изв");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);
        NormativeRate rate = new NormativeRate();
        rate.setSourcebookId(book.getId());
        rate.setCode("22-ИЗВ-1");
        rate.setName("Техническое обслуживание извещателя охранного");
        rate.setUnit("1 шт.");
        rate.setLaborCost(new BigDecimal("50.00"));
        rateRepository.saveAndFlush(rate);

        long sys = facilitySystem("Объект-изв");
        equip(sys, "Извещатель охранный ИО102", "СМК-1");
        // ИИ выключен → расценка из поиска, но периодичность — дефолт по типу
        Mockito.when(aiClient.hasMatchModel()).thenReturn(false);
        long estId = estimateFor(estFacility(sys));
        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys).header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(1));

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode row = view.get("rows").get(0).get("row");
        assertThat(row.get("periodicity").asText()).isEqualTo("раз в 6 мес.");
        assertThat(row.get("opsPerYear").asDouble()).isEqualTo(2.0);
    }

    /** Оборудование с двумя операциями в эталоне → две строки (осмотр + ТО). */
    @Test
    void twoEtalonOperationsGiveTwoRows() throws Exception {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("Сборник ивэпр");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);
        for (String[] r : new String[][]{
                {"22-2201-78-1/1", "Технический осмотр источника питания"},
                {"22-2203-91-1/1", "Техническое обслуживание источника питания"}}) {
            NormativeRate rate = new NormativeRate();
            rate.setSourcebookId(book.getId());
            rate.setCode(r[0]);
            rate.setName(r[1]);
            rate.setUnit("1 шт.");
            rate.setLaborCost(new BigDecimal("100.00"));
            rateRepository.saveAndFlush(rate);
        }

        // эталон: «Источник вторичного электропитания» с ДВУМЯ операциями
        UniqueEquipment ue = new UniqueEquipment();
        ue.setNormKey("источник вторичного электропитания|old||скуд");
        ue.setEquipKey("источник вторичного электропитания|old|");
        ue.setSystemType("скуд");
        ue.setName("Источник вторичного электропитания");
        ue.setModel("OLD");
        ue = uniqueRepository.saveAndFlush(ue);
        addDecision(ue.getId(), "осмотр", "Технический осмотр", "22-2201-78-1/1", "раз в 1 мес.", "12");
        addDecision(ue.getId(), "то", "Техническое обслуживание", "22-2203-91-1/1", "раз в 6 мес.", "2");

        long sys = facilitySystem("Объект-ивэпр");
        // два источника РАЗНЫХ моделей с суффиксами — совпадение с эталоном по подмножеству слов
        equip(sys, "Источник вторичного электропитания резервированный (для STR-1AP)", "ИВЭПР 2x7");
        equip(sys, "Источник вторичного электропитания резервированный (для STR20-IP)", "ИВЭПР 2x17");
        Mockito.when(aiClient.hasMatchModel()).thenReturn(true);
        long estId = estimateFor(estFacility(sys));
        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys).header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(4));   // по 2 строки (осмотр + ТО) на каждый источник

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        // у каждого источника обе операции: осмотр (…78, раз в 1 мес) и ТО (…91, раз в 6 мес)
        java.util.Map<String, java.util.Set<String>> byQty = new java.util.HashMap<>();
        view.get("rows").forEach(r -> {
            assertThat(r.get("row").get("matchSource").asText()).isEqualTo("ETALON_TYPE");
            byQty.computeIfAbsent(r.get("row").get("equipmentType").asText(), k -> new java.util.HashSet<>())
                    .add(r.get("row").get("rateCode").asText());
        });
        assertThat(byQty.get("ИВЭПР 2x7")).containsExactlyInAnyOrder("22-2201-78-1/1", "22-2203-91-1/1");
        assertThat(byQty.get("ИВЭПР 2x17")).containsExactlyInAnyOrder("22-2201-78-1/1", "22-2203-91-1/1");
        Mockito.verify(aiClient, Mockito.never()).completeMatch(anyString(), anyString());
    }

    /**
     * Синонимы: в эталоне «Блок питания», на объекте «Источник вторичного электропитания
     * резервированный (для STR-1AP)» — общих слов нет, тип определяет ИИ, а обе операции
     * (осмотр 10/год + ТО 2/год) берутся из эталона детерминированно.
     */
    @Test
    void synonymTypeMatchedByAiTakesAllEtalonOperations() throws Exception {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("Сборник бп");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);
        for (String[] r : new String[][]{
                {"22-2201-78-1/1", "Технический осмотр источника вторичного электропитания"},
                {"22-2203-91-1/1", "Техническое обслуживание источника вторичного электропитания"}}) {
            NormativeRate rate = new NormativeRate();
            rate.setSourcebookId(book.getId());
            rate.setCode(r[0]);
            rate.setName(r[1]);
            rate.setUnit("1 шт.");
            rate.setLaborCost(new BigDecimal("100.00"));
            rateRepository.saveAndFlush(rate);
        }

        // эталон: «Блок питания» с ДРУГОЙ моделью (совпадения по модели нет — только через ИИ)
        UniqueEquipment ue = new UniqueEquipment();
        ue.setNormKey("блок питания|бирп 12-10||скуд");
        ue.setEquipKey("блок питания|бирп 12-10|");
        ue.setSystemType("скуд");
        ue.setName("Блок питания");
        ue.setModel("БИРП 12-10");
        ue = uniqueRepository.saveAndFlush(ue);
        addDecision(ue.getId(), "осмотр", "Технический осмотр источника вторичного электропитания",
                "22-2201-78-1/1", "раз в 1 мес.", "10");
        addDecision(ue.getId(), "то", "Техническое обслуживание источника вторичного электропитания",
                "22-2203-91-1/1", "раз в 6 мес.", "2");

        long sys = facilitySystem("Объект-синоним");
        equip(sys, "Источник вторичного электропитания резервированный (для STR-1AP)", "ИВЭПР 12/2 RS-R3 2x7 БР");

        Mockito.when(aiClient.hasMatchModel()).thenReturn(true);
        // ИИ сопоставляет тип: отвечает индексом единственного типа эталона
        Mockito.when(aiClient.completeMatch(anyString(), anyString())).thenReturn("{\"index\": 1}");

        long estId = estimateFor(estFacility(sys));
        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys).header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(2));   // осмотр + ТО

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        java.util.Map<String, Double> perYearByCode = new java.util.HashMap<>();
        view.get("rows").forEach(r -> {
            assertThat(r.get("row").get("matchSource").asText()).isEqualTo("AI_TYPE");
            assertThat(r.get("row").get("needsReview").asBoolean()).isFalse();
            perYearByCode.put(r.get("row").get("rateCode").asText(), r.get("row").get("opsPerYear").asDouble());
        });
        assertThat(perYearByCode).containsOnlyKeys("22-2201-78-1/1", "22-2203-91-1/1");
        assertThat(perYearByCode.get("22-2201-78-1/1")).isEqualTo(10.0);   // осмотр из эталона, не 12
        assertThat(perYearByCode.get("22-2203-91-1/1")).isEqualTo(2.0);
    }

    /**
     * Реальный случай пользователя: в эталоне ОДИН ИВЭПР («Блок питания») с двумя работами,
     * на объекте ДВА разных ИВЭПР с другим наименованием. Обе позиции должны получить обе
     * работы эталона по совпадению МОДЕЛИ — детерминированно, без ИИ.
     */
    @Test
    void twoObjectIvepersMatchSingleEtalonIveprByModelWithoutAi() throws Exception {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("Сборник ивэпр-модель");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);
        for (String[] r : new String[][]{
                {"22-2201-78-1/1", "Технический осмотр источника вторичного электропитания"},
                {"22-2203-91-1/1", "Техническое обслуживание источника вторичного электропитания"}}) {
            NormativeRate rate = new NormativeRate();
            rate.setSourcebookId(book.getId());
            rate.setCode(r[0]);
            rate.setName(r[1]);
            rate.setUnit("1 шт.");
            rate.setLaborCost(new BigDecimal("100.00"));
            rateRepository.saveAndFlush(rate);
        }

        // эталон: «Блок питания» ИВЭПР 12/2 RSR3 2x7-Р БР — осмотр 10/год + ТО 2/год
        UniqueEquipment ue = new UniqueEquipment();
        ue.setNormKey("блок питания|ивэпр 12/2 rsr3 2x7-р бр||скуд");
        ue.setEquipKey("блок питания|ивэпр 12/2 rsr3 2x7-р бр|");
        ue.setSystemType("скуд");
        ue.setName("Блок питания");
        ue.setModel("ИВЭПР 12/2 RSR3 2x7-Р БР");
        ue = uniqueRepository.saveAndFlush(ue);
        addDecision(ue.getId(), "осмотр", "Технический осмотр источника вторичного электропитания",
                "22-2201-78-1/1", "раз в 1 мес.", "10");
        addDecision(ue.getId(), "то", "Техническое обслуживание источника вторичного электропитания",
                "22-2203-91-1/1", "раз в 6 мес.", "2");

        long sys = facilitySystem("Объект-2ивэпр");
        // два разных ИВЭПР на объекте, наименование не пересекается с «Блок питания»
        equip(sys, "Источник вторичного электропитания резервированный (для STR-1AP)", "ИВЭПР 12/2 RS-R3 2x7 БР");
        equip(sys, "Источник вторичного электропитания резервированный (для STR20-IP)", "ИВЭПР 12/2 RS-R3 2х7 БР");

        // ИИ доступен, но НЕ должен вызываться — совпадение по модели детерминированно
        Mockito.when(aiClient.hasMatchModel()).thenReturn(true);

        long estId = estimateFor(estFacility(sys));
        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys).header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(4));   // по 2 работы на каждый ИВЭПР

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        java.util.Map<String, java.util.Map<String, Double>> byModel = new java.util.HashMap<>();
        view.get("rows").forEach(r -> {
            JsonNode row = r.get("row");
            assertThat(row.get("matchSource").asText()).isEqualTo("ETALON_TYPE");
            byModel.computeIfAbsent(row.get("equipmentType").asText(), k -> new java.util.HashMap<>())
                    .put(row.get("rateCode").asText(), row.get("opsPerYear").asDouble());
        });
        assertThat(byModel).hasSize(2);
        byModel.values().forEach(ops -> {
            assertThat(ops).containsOnlyKeys("22-2201-78-1/1", "22-2203-91-1/1");
            assertThat(ops.get("22-2201-78-1/1")).isEqualTo(10.0);   // осмотр из эталона
            assertThat(ops.get("22-2203-91-1/1")).isEqualTo(2.0);    // ТО из эталона
        });
        Mockito.verify(aiClient, Mockito.never()).completeMatch(anyString(), anyString());
    }

    /**
     * В эталоне одна и та же расценка попала в разные категории («то» у одной модели и
     * «проверка» у другой — из-за формулировки «ТО …, проверка АКБ»). Строка не должна
     * дублироваться: одна расценка = одна работа.
     */
    @Test
    void sameRateInDifferentCategoriesDoesNotDuplicateRow() throws Exception {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("Сборник дубль");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);
        for (String[] r : new String[][]{
                {"22-2201-78-1/1", "Технический осмотр источника вторичного электропитания"},
                {"22-2203-91-1/1", "Техническое обслуживание источника вторичного электропитания"}}) {
            NormativeRate rate = new NormativeRate();
            rate.setSourcebookId(book.getId());
            rate.setCode(r[0]);
            rate.setName(r[1]);
            rate.setUnit("1 шт.");
            rate.setLaborCost(new BigDecimal("100.00"));
            rateRepository.saveAndFlush(rate);
        }

        // две эталонные записи с одним наименованием «Блок питания», но разными моделями:
        // у одной ТО сохранено с категорией «то», у другой — «проверка» (та же расценка 91)
        UniqueEquipment a = new UniqueEquipment();
        a.setNormKey("блок питания|бирп 12-10||скуд");
        a.setEquipKey("блок питания|бирп 12-10|");
        a.setSystemType("скуд");
        a.setName("Блок питания");
        a.setModel("БИРП 12-10");
        a = uniqueRepository.saveAndFlush(a);
        addDecision(a.getId(), "осмотр", "Технический осмотр", "22-2201-78-1/1", "раз в 1 мес.", "10");
        addDecision(a.getId(), "то", "Техническое обслуживание", "22-2203-91-1/1", "раз в 6 мес.", "2");

        UniqueEquipment b = new UniqueEquipment();
        b.setNormKey("блок питания|ивэпр 12/2 rsr3 2x7-р бр||скуд");
        b.setEquipKey("блок питания|ивэпр 12/2 rsr3 2x7-р бр|");
        b.setSystemType("скуд");
        b.setName("Блок питания");
        b.setModel("ИВЭПР 12/2 RSR3 2x7-Р БР");
        b = uniqueRepository.saveAndFlush(b);
        addDecision(b.getId(), "осмотр", "Технический осмотр", "22-2201-78-1/1", "раз в 1 мес.", "10");
        addDecision(b.getId(), "проверка", "Техническое обслуживание, проверка АКБ",
                "22-2203-91-1/1", "раз в 6 мес.", "2");

        long sys = facilitySystem("Объект-дубль");
        equip(sys, "Источник вторичного электропитания резервированный (для STR-1AP)", "ИВЭПР 12/2 RS-R3 2x7 БР");
        Mockito.when(aiClient.hasMatchModel()).thenReturn(true);

        long estId = estimateFor(estFacility(sys));
        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys).header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(2));   // осмотр + ТО, без дубля ТО

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        java.util.List<String> codes = new java.util.ArrayList<>();
        view.get("rows").forEach(r -> codes.add(r.get("row").get("rateCode").asText()));
        assertThat(codes).containsExactlyInAnyOrder("22-2201-78-1/1", "22-2203-91-1/1");
    }

    private void addDecision(long ueId, String opKey, String opName, String code, String periodicity, String perYear) {
        EstimateRateDecision d = new EstimateRateDecision();
        d.setUniqueEquipmentId(ueId);
        d.setOperationKey(opKey);
        d.setOperationName(opName);
        d.setRateCode(code);
        d.setPeriodicity(periodicity);
        d.setPerYear(new BigDecimal(perYear));
        d.setSource(EstimateRateDecision.SOURCE_REFERENCE);
        decisionRepository.saveAndFlush(d);
    }
}
