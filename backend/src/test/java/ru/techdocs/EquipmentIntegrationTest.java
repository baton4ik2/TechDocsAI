package ru.techdocs;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class EquipmentIntegrationTest extends IntegrationTestBase {

    private long facility(String name, String... systems) throws Exception {
        StringBuilder sys = new StringBuilder();
        for (int i = 0; i < systems.length; i++) {
            if (i > 0) sys.append(',');
            sys.append('"').append(systems[i]).append('"');
        }
        String resp = mockMvc.perform(post("/api/facilities").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"systems\":[" + sys + "]}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private long addEquipment(long facilityId, String name, String model, int qty) throws Exception {
        String resp = mockMvc.perform(post("/api/equipment").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"facilityId\":" + facilityId + ",\"name\":\"" + name +
                                "\",\"model\":\"" + model + "\",\"quantity\":" + qty + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    @Test
    void createUpdateDeleteEquipment() throws Exception {
        long f = facility("Объект");
        long eq = addEquipment(f, "Извещатель", "ДИП-34А", 100);

        mockMvc.perform(put("/api/equipment/" + eq).header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"facilityId\":" + f + ",\"name\":\"Извещатель\",\"model\":\"ДИП-34А\",\"quantity\":150}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(150));

        mockMvc.perform(delete("/api/equipment/" + eq).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
    }

    @Test
    void confirmBatchSetsStatus() throws Exception {
        long f = facility("Объект");
        long e1 = addEquipment(f, "А", "М1", 1);
        long e2 = addEquipment(f, "Б", "М2", 2);

        mockMvc.perform(post("/api/equipment/confirm").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"ids\":[" + e1 + "," + e2 + "]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/equipment?facilityId=" + f).header("Authorization", bearer()))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"));
    }

    @Test
    void mergeSumsQuantities() throws Exception {
        long f = facility("Объект");
        long e1 = addEquipment(f, "Камера", "LTV", 5);
        long e2 = addEquipment(f, "Камера", "LTV", 3);

        mockMvc.perform(post("/api/equipment/merge").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"ids\":[" + e1 + "," + e2 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(8));
    }

    @Test
    void importFlatXlsxWithDuplicatePreview() throws Exception {
        long f = facility("Объект");
        byte[] xlsx = flatWorkbook();

        String preview = mockMvc.perform(multipart("/api/equipment/import/preview")
                        .file(new MockMultipartFile("file", "spec.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx))
                        .param("facilityId", String.valueOf(f))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode items = json.readTree(preview);
        // 3 строки: 2 камеры (дубль-группа) + 1 датчик
        boolean hasDuplicateGroup = false;
        for (JsonNode i : items) {
            if (i.hasNonNull("duplicateGroup")) hasDuplicateGroup = true;
        }
        org.assertj.core.api.Assertions.assertThat(hasDuplicateGroup).isTrue();

        // импортируем объединённую камеру + датчик
        String importBody = "{\"facilityId\":" + f + ",\"fileName\":\"spec.xlsx\",\"items\":[" +
                "{\"name\":\"Камера\",\"model\":\"LTV\",\"quantity\":8,\"unit\":\"шт.\"}," +
                "{\"name\":\"Датчик\",\"model\":\"Д-1\",\"quantity\":4,\"unit\":\"шт.\"}]}";
        mockMvc.perform(post("/api/equipment/import").header("Authorization", bearer())
                        .contentType("application/json").content(importBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2));

        mockMvc.perform(get("/api/equipment?facilityId=" + f).header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void importWideRegistryCreatesSystems() throws Exception {
        long f = facility("Объект"); // без систем
        // импорт с именами систем из файла
        String body = "{\"facilityId\":" + f + ",\"fileName\":\"reestr.xlsx\",\"items\":[" +
                "{\"name\":\"Камера\",\"model\":\"LTV\",\"quantity\":10,\"unit\":\"шт.\",\"systemName\":\"Видеонаблюдение\"}," +
                "{\"name\":\"Извещатель\",\"model\":\"ИП\",\"quantity\":20,\"unit\":\"шт.\",\"systemName\":\"Пожарная сигнализация\"}]}";
        mockMvc.perform(post("/api/equipment/import").header("Authorization", bearer())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2));

        // системы созданы автоматически
        mockMvc.perform(get("/api/facilities/" + f + "/systems").header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private byte[] flatWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Спецификация");
            String[][] rows = {
                    {"Наименование", "Модель", "Кол-во"},
                    {"Камера", "LTV", "5"},
                    {"Камера", "LTV", "3"},
                    {"Датчик", "Д-1", "4"},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }
}
