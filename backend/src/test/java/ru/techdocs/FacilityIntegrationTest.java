package ru.techdocs;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class FacilityIntegrationTest extends IntegrationTestBase {

    private long createFacility(String name, String... systems) throws Exception {
        StringBuilder sys = new StringBuilder();
        for (int i = 0; i < systems.length; i++) {
            if (i > 0) sys.append(',');
            sys.append('"').append(systems[i]).append('"');
        }
        String body = "{\"name\":\"" + name + "\",\"systems\":[" + sys + "]}";
        String resp = mockMvc.perform(post("/api/facilities").header("Authorization", bearer())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    @Test
    void createsAndReadsFacilityWithStats() throws Exception {
        long id = createFacility("Тестовый объект", "АПС", "СКУД");
        mockMvc.perform(get("/api/facilities/" + id).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Тестовый объект"))
                .andExpect(jsonPath("$.systemCount").value(2))
                .andExpect(jsonPath("$.documentCount").value(0));
    }

    @Test
    void addsAndRenamesSystem() throws Exception {
        long id = createFacility("Объект");
        String resp = mockMvc.perform(post("/api/facilities/" + id + "/systems")
                        .header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Вентиляция\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long sysId = json.readTree(resp).get("id").asLong();

        // переименование — тоже только в системы справочника, со стандартным названием
        mockMvc.perform(patch("/api/facilities/" + id + "/systems/" + sysId)
                        .header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Кондиционирование\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Кондиционирование"));

        // произвольное имя вне справочника отклоняется: системы — константа приложения
        mockMvc.perform(post("/api/facilities/" + id + "/systems")
                        .header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Фонтаны\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void differentSpellingsOfOneSystemCollapseOnCreate() throws Exception {
        // «апс» и «Пожарная сигнализация» — одна система: при создании объекта
        // остаётся одна запись со стандартным названием
        long id = createFacility("Объект-дубли", "апс", "Пожарная сигнализация");
        mockMvc.perform(get("/api/facilities/" + id + "/systems").header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Пожарная сигнализация"));
    }

    @Test
    void mergesSystemsMovingEquipment() throws Exception {
        long id = createFacility("Объект", "АПС", "Охранная сигнализация");
        JsonNode systems = json.readTree(mockMvc.perform(
                        get("/api/facilities/" + id + "/systems").header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString());
        long apsId = systems.get(0).get("id").asLong();
        long psId = systems.get(1).get("id").asLong();

        // добавляем оборудование в «Пожарную сигнализацию»
        mockMvc.perform(post("/api/equipment").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"facilityId\":" + id + ",\"engineeringSystemId\":" + psId +
                                ",\"name\":\"Извещатель\",\"model\":\"ИП-1\",\"quantity\":10}"))
                .andExpect(status().isOk());

        // объединяем «Пожарную сигнализацию» → в АПС
        mockMvc.perform(post("/api/facilities/" + id + "/systems/" + psId + "/merge")
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"targetSystemId\":" + apsId + "}"))
                .andExpect(status().isNoContent());

        // осталась одна система, оборудование теперь в АПС
        mockMvc.perform(get("/api/facilities/" + id + "/systems").header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment?facilityId=" + id + "&systemId=" + apsId)
                        .header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].model").value("ИП-1"));
    }

    @Test
    void deletesFacility() throws Exception {
        long id = createFacility("На удаление");
        mockMvc.perform(delete("/api/facilities/" + id).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/facilities/" + id).header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchFiltersByName() throws Exception {
        createFacility("Уникальный-ЖК-Абрикос");
        mockMvc.perform(get("/api/facilities?search=Абрикос").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Уникальный-ЖК-Абрикос"));
    }
}
