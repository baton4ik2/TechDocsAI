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

        // эталон системы СКУД: считыватель другой модели → расценка 21-СКУД-1, периодичность «раз в год»
        UniqueEquipment ue = new UniqueEquipment();
        ue.setNormKey("считыватель|old-model||скуд");
        ue.setEquipKey("считыватель|old-model|");
        ue.setSystemType("скуд");
        ue.setName("Считыватель");
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
}
