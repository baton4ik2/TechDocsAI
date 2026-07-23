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
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class UniqueEquipmentIntegrationTest extends IntegrationTestBase {

    @Autowired EngineeringSystemRepository systemRepository;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired NormativeSourcebookRepository sourcebookRepository;
    @Autowired NormativeRateRepository rateRepository;
    @Autowired PkmDocumentRepository pkmDocumentRepository;
    @Autowired PkmOperationRepository pkmOperationRepository;

    private long facility() throws Exception {
        String resp = mockMvc.perform(post("/api/facilities").header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Объект\",\"systems\":[]}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private long system(long facilityId) {
        EngineeringSystem s = new EngineeringSystem();
        s.setFacilityId(facilityId);
        s.setName("СКУД");
        return systemRepository.saveAndFlush(s).getId();
    }

    private void equipment(long facilityId, long systemId) {
        Equipment e = new Equipment();
        e.setFacilityId(facilityId);
        e.setEngineeringSystemId(systemId);
        e.setName("Извещатель пожарный дымовой");
        e.setModel("ИП212");
        e.setQuantity(new BigDecimal("5"));
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
        rate.setName("Техническое обслуживание извещателя пожарного дымового");
        rate.setUnit("1 шт.");
        rate.setLaborCost(new BigDecimal("139.33"));
        rate.setMaterialCost(new BigDecimal("50.40"));
        rateRepository.saveAndFlush(rate);
    }

    private void seedPkm() {
        PkmDocument d = new PkmDocument();
        d.setName("Регламент СКУД");
        d.setSystemType("СКУД");
        d.setStatus(PkmDocument.STATUS_READY);
        d = pkmDocumentRepository.saveAndFlush(d);
        PkmOperation op = new PkmOperation();
        op.setPkmId(d.getId());
        op.setSystemType("СКУД");
        op.setPosition(1);
        op.setOperationName("Техническое обслуживание УКД");
        op.setPeriodicity("Раз в год");
        op.setPeriodicityPerYear(BigDecimal.ONE);
        pkmOperationRepository.saveAndFlush(op);
    }

    private long syncAndGetUniqueId() throws Exception {
        mockMvc.perform(post("/api/unique-equipment/sync").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(1));
        String list = mockMvc.perform(get("/api/unique-equipment").header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode arr = json.readTree(list);
        org.assertj.core.api.Assertions.assertThat(arr).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(arr.get(0).get("objectCount").asInt()).isEqualTo(1);
        return arr.get(0).get("equipment").get("id").asLong();
    }

    @Test
    void syncLinksEquipmentAndIsIdempotent() throws Exception {
        long f = facility();
        equipment(f, system(f));
        syncAndGetUniqueId();
        // повторная синхронизация ничего не добавляет
        mockMvc.perform(post("/api/unique-equipment/sync").header("Authorization", bearer()))
                .andExpect(jsonPath("$.linked").value(0));
    }

    @Test
    void plannedWorksCrud() throws Exception {
        long f = facility();
        equipment(f, system(f));
        long ue = syncAndGetUniqueId();

        String w = mockMvc.perform(post("/api/unique-equipment/" + ue + "/planned-works")
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"workType\":\"осмотр\",\"name\":\"Технический осмотр\",\"periodicity\":\"Ежемесячно\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long workId = json.readTree(w).get("id").asLong();
        // периодичность нормализована из текста
        org.assertj.core.api.Assertions.assertThat(json.readTree(w).get("periodicityPerYear").asInt()).isEqualTo(12);

        mockMvc.perform(get("/api/unique-equipment/" + ue + "/planned-works").header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(delete("/api/unique-equipment/planned-works/" + workId).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
    }

    @Test
    void draftUsesPassportPlannedWorksOverPkm() throws Exception {
        long f = facility();
        long sys = system(f);
        equipment(f, sys);
        seedRate();
        seedPkm();
        long ue = syncAndGetUniqueId();

        // две плановые работы из паспорта → две строки сметы (а не одна из ПКМ)
        addWork(ue, "осмотр", "Технический осмотр", "Ежемесячно");
        addWork(ue, "ТО", "Техническое обслуживание", "Два раза в год");

        String est = mockMvc.perform(post("/api/estimates?facilityId=" + f).header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Черновик\",\"systemId\":" + sys + "}"))
                .andReturn().getResponse().getContentAsString();
        long estId = json.readTree(est).get("id").asLong();

        mockMvc.perform(post("/api/estimates/" + estId + "/generate").header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(2));  // по строке на плановую работу, не ПКМ

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        org.assertj.core.api.Assertions.assertThat(view.get("rows")).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(view.get("rows").get(0).get("row").get("periodicity").asText())
                .isEqualTo("Ежемесячно");
        org.assertj.core.api.Assertions.assertThat(view.get("rows").get(1).get("row").get("opsPerYear").asDouble())
                .isEqualTo(2.0);
    }

    private void addWork(long ue, String type, String name, String periodicity) throws Exception {
        mockMvc.perform(post("/api/unique-equipment/" + ue + "/planned-works").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"workType\":\"" + type + "\",\"name\":\"" + name + "\",\"periodicity\":\"" + periodicity + "\"}"))
                .andExpect(status().isOk());
    }
}
