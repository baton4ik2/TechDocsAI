package ru.techdocs;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;
import ru.techdocs.normative.NormativeRate;
import ru.techdocs.normative.NormativeRateRepository;
import ru.techdocs.normative.NormativeSourcebook;
import ru.techdocs.normative.NormativeSourcebookRepository;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class EstimateDecisionIntegrationTest extends IntegrationTestBase {

    @Autowired EngineeringSystemRepository systemRepository;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired NormativeSourcebookRepository sourcebookRepository;
    @Autowired NormativeRateRepository rateRepository;

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

    /** Мини-эталон XLSX: заголовки + одна строка. */
    private byte[] referenceXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Расчёт");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Наименование оборудования");
            h.createCell(1).setCellValue("Тип оборудования");
            h.createCell(2).setCellValue("Производитель оборудования");
            h.createCell(3).setCellValue("Наименование мероприятия");
            h.createCell(4).setCellValue("Шифр расценки");
            h.createCell(5).setCellValue("периодичность операции");
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("Извещатель пожарный дымовой");
            r.createCell(1).setCellValue("ИП212");
            r.createCell(2).setCellValue("Рубеж");
            r.createCell(3).setCellValue("Техническое обслуживание извещателя");
            r.createCell(4).setCellValue("22-2203-128-1/1");
            r.createCell(5).setCellValue("раз в 6 мес.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private long facility(String name) throws Exception {
        String resp = mockMvc.perform(post("/api/facilities").header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"" + name + "\",\"systems\":[]}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private long system(long facilityId) {
        EngineeringSystem s = new EngineeringSystem();
        s.setFacilityId(facilityId);
        s.setName("АПС");
        return systemRepository.saveAndFlush(s).getId();
    }

    private void equipment(long facilityId, long systemId) {
        Equipment e = new Equipment();
        e.setFacilityId(facilityId);
        e.setEngineeringSystemId(systemId);
        e.setName("Извещатель пожарный дымовой");
        e.setModel("ИП212");
        e.setManufacturer("Рубеж");
        e.setQuantity(new BigDecimal("7"));
        equipmentRepository.saveAndFlush(e);
    }

    @Test
    void importedReferenceIsReusedOnAnotherObject() throws Exception {
        seedRate();
        // 1) загружаем эталон — наполняем память и создаём уникальное оборудование
        mockMvc.perform(multipart("/api/estimates/import-reference")
                        .file(new MockMultipartFile("file", "etalon.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", referenceXlsx()))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get("/api/estimate-decisions").header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(1));

        // 2) на другом объекте то же оборудование → сборка берёт расценку из памяти, без ИИ
        long f = facility("Новый объект");
        long sys = system(f);
        equipment(f, sys);
        String est = mockMvc.perform(post("/api/estimates?facilityId=" + f).header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Смета\"}"))
                .andReturn().getResponse().getContentAsString();
        long estId = json.readTree(est).get("id").asLong();

        mockMvc.perform(post("/api/estimates/" + estId + "/generate?systemIds=" + sys)
                        .header("Authorization", bearer()))
                .andExpect(jsonPath("$.created").value(1));

        JsonNode view = json.readTree(mockMvc.perform(get("/api/estimates/" + estId).header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode row = view.get("rows").get(0).get("row");
        org.assertj.core.api.Assertions.assertThat(row.get("rateCode").asText()).isEqualTo("22-2203-128-1/1");
        org.assertj.core.api.Assertions.assertThat(row.get("opsPerYear").asDouble()).isEqualTo(2.0); // раз в 6 мес.
        org.assertj.core.api.Assertions.assertThat(row.get("priceZp").asDouble()).isEqualTo(139.33); // из каталога
        // строка из памяти эталона — проверять не нужно
        org.assertj.core.api.Assertions.assertThat(row.get("matchSource").asText()).isEqualTo("LEARNED");
        org.assertj.core.api.Assertions.assertThat(row.get("needsReview").asBoolean()).isFalse();
    }

    /** Реальный эталон повторяет оборудование на многих строках — не должно падать. */
    private byte[] duplicatesXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Расчёт");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Наименование оборудования");
            h.createCell(1).setCellValue("Тип оборудования");
            h.createCell(2).setCellValue("Производитель оборудования");
            h.createCell(3).setCellValue("Наименование мероприятия");
            h.createCell(4).setCellValue("Шифр расценки");
            h.createCell(5).setCellValue("периодичность операции");
            String[][] rows = {
                    {"Блок питания", "БП", "Рубеж", "Технический осмотр", "22-2201-78-1/1", "раз в 1 мес."},
                    {"Блок питания", "БП", "Рубеж", "Техническое обслуживание", "22-2203-91-1/1", "раз в 6 мес."},
                    {"Блок питания", "БП", "Рубеж", "Техническое обслуживание", "22-2203-91-1/1", "раз в 6 мес."},
            };
            int r = 1;
            for (String[] row : rows) {
                Row rw = sheet.createRow(r++);
                for (int c = 0; c < row.length; c++) rw.createCell(c).setCellValue(row[c]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void importDeduplicatesRepeatedEquipment() throws Exception {
        mockMvc.perform(multipart("/api/estimates/import-reference")
                        .file(new MockMultipartFile("file", "etalon.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", duplicatesXlsx()))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))   // осмотр + ТО (третья строка — дубль ТО)
                .andExpect(jsonPath("$.rows").value(3));

        mockMvc.perform(get("/api/estimate-decisions").header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void promoteStoresDecisionsFromEstimate() throws Exception {
        seedRate();
        long f = facility("Объект");
        String est = mockMvc.perform(post("/api/estimates?facilityId=" + f).header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Смета\"}"))
                .andReturn().getResponse().getContentAsString();
        long estId = json.readTree(est).get("id").asLong();
        // строка с оборудованием и расценкой
        mockMvc.perform(post("/api/estimates/" + estId + "/rows").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"equipmentName\":\"Извещатель пожарный дымовой\",\"equipmentType\":\"ИП212\","
                                + "\"manufacturer\":\"Рубеж\",\"operationName\":\"Техническое обслуживание\","
                                + "\"rateCode\":\"22-2203-128-1/1\",\"qty\":1,\"periodicity\":\"раз в 6 мес.\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/estimates/" + estId + "/promote").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(1));
        mockMvc.perform(get("/api/estimate-decisions").header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
