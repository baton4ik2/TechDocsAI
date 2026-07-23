package ru.techdocs;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;
import ru.techdocs.normative.NormativeRate;
import ru.techdocs.normative.NormativeRateRepository;
import ru.techdocs.normative.NormativeSourcebook;
import ru.techdocs.normative.NormativeSourcebookRepository;
import ru.techdocs.pkm.PkmDocument;
import ru.techdocs.pkm.PkmDocumentRepository;
import ru.techdocs.pkm.PkmOperation;
import ru.techdocs.pkm.PkmOperationRepository;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class EstimateDraftIntegrationTest extends IntegrationTestBase {

    @Autowired EngineeringSystemRepository systemRepository;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired NormativeSourcebookRepository sourcebookRepository;
    @Autowired NormativeRateRepository rateRepository;
    @Autowired PkmDocumentRepository pkmDocumentRepository;
    @Autowired PkmOperationRepository pkmOperationRepository;

    private long facility() throws Exception {
        String resp = mockMvc.perform(post("/api/facilities").header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Лицей\",\"systems\":[]}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private long system(long facilityId, String name) {
        EngineeringSystem s = new EngineeringSystem();
        s.setFacilityId(facilityId);
        s.setName(name);
        return systemRepository.saveAndFlush(s).getId();
    }

    private void equipment(long facilityId, long systemId, String name, String model, String qty) {
        Equipment e = new Equipment();
        e.setFacilityId(facilityId);
        e.setEngineeringSystemId(systemId);
        e.setName(name);
        e.setModel(model);
        e.setQuantity(new BigDecimal(qty));
        equipmentRepository.saveAndFlush(e);
    }

    private void seedRate() {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("Сборник 22");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);
        NormativeRate rate = new NormativeRate();
        rate.setSourcebookId(book.getId());
        rate.setCode("22-2203-128-1/1");
        rate.setName("Техническое обслуживание извещателя пожарного дымового - полугодовое");
        rate.setUnit("1 шт.");
        rate.setLaborCost(new BigDecimal("139.33"));
        rate.setMachineCost(BigDecimal.ZERO);
        rate.setMachineLabor(BigDecimal.ZERO);
        rate.setMaterialCost(new BigDecimal("50.40"));
        rate.setLaborHours(new BigDecimal("0.20"));
        rateRepository.saveAndFlush(rate);
    }

    private void seedPkm(String systemType) {
        PkmDocument doc = new PkmDocument();
        doc.setName("Регламент СКУД");
        doc.setSystemType(systemType);
        doc.setStatus(PkmDocument.STATUS_READY);
        doc = pkmDocumentRepository.saveAndFlush(doc);
        PkmOperation op = new PkmOperation();
        op.setPkmId(doc.getId());
        op.setSystemType(systemType);
        op.setPosition(1);
        op.setOperationName("Техническое обслуживание устройства контроля доступа");
        op.setPeriodicity("Ежемесячно");
        op.setPeriodicityPerYear(new BigDecimal("12"));
        pkmOperationRepository.saveAndFlush(op);
    }

    private long estimate(long facilityId, long systemId) throws Exception {
        String resp = mockMvc.perform(post("/api/estimates?facilityId=" + facilityId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"name\":\"Черновик\",\"systemId\":" + systemId + "}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    @Test
    void generatesRowsFromRegistryWithPkmPeriodicityAndCatalogPrices() throws Exception {
        long f = facility();
        long sys = system(f, "СКУД");
        equipment(f, sys, "Извещатель пожарный дымовой", "ИП212", "5");
        seedRate();
        seedPkm("СКУД");
        long est = estimate(f, sys);

        // генерация черновика: 1 строка на оборудование
        mockMvc.perform(post("/api/estimates/" + est + "/generate").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        // строка: расценка подобрана по каталогу, цены заполнены,
        // периодичность — из ПКМ (Ежемесячно → 12), количество из реестра
        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + est)
                        .header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode row = view.get("rows").get(0).get("row");
        org.assertj.core.api.Assertions.assertThat(row.get("rateCode").asText()).isEqualTo("22-2203-128-1/1");
        org.assertj.core.api.Assertions.assertThat(row.get("priceZp").asDouble()).isEqualTo(139.33);
        org.assertj.core.api.Assertions.assertThat(row.get("opsPerYear").asDouble()).isEqualTo(12.0);
        org.assertj.core.api.Assertions.assertThat(row.get("periodicity").asText()).isEqualTo("Ежемесячно");
        org.assertj.core.api.Assertions.assertThat(row.get("qty").asDouble()).isEqualTo(5.0);
        // расчёт: ЗП = 139.33 * (5*12) = 8359.8
        org.assertj.core.api.Assertions.assertThat(view.get("rows").get(0).get("calc").get("zp").asDouble())
                .isEqualTo(8359.8);
    }

    @Test
    void generatesBlocksPerSelectedSystem() throws Exception {
        long f = facility();
        long aps = system(f, "АПС");
        long soue = system(f, "СОУЭ");
        equipment(f, aps, "Извещатель пожарный", "ИП212", "3");
        equipment(f, soue, "Оповещатель речевой", "ОР1", "2");
        // расценка, совпадающая по слову «обслуживание» для обоих
        seedRate();
        long est = estimate(f, aps);

        // генерация по двум системам → две строки с разными разделами (блоками)
        mockMvc.perform(post("/api/estimates/" + est + "/generate?systemIds=" + aps + "&systemIds=" + soue)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2));

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + est).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        java.util.List<String> sections = new java.util.ArrayList<>();
        view.get("rows").forEach(r -> sections.add(r.get("row").get("section").asText()));
        org.assertj.core.api.Assertions.assertThat(sections).containsExactlyInAnyOrder("АПС", "СОУЭ");
    }

    @Test
    void secondGenerateSkipsExistingEquipment() throws Exception {
        long f = facility();
        long sys = system(f, "СКУД");
        equipment(f, sys, "Извещатель пожарный дымовой", "ИП212", "5");
        seedRate();
        long est = estimate(f, sys);

        mockMvc.perform(post("/api/estimates/" + est + "/generate").header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(1));
        // повторный запуск не дублирует уже добавленное оборудование
        mockMvc.perform(post("/api/estimates/" + est + "/generate").header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(1));
    }
}
