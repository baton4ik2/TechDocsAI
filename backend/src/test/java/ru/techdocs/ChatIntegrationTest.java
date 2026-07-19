package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class ChatIntegrationTest extends IntegrationTestBase {

    private long facility() throws Exception {
        String resp = mockMvc.perform(post("/api/facilities").header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Объект\",\"systems\":[]}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private void addEquipment(long f, String name, String model, int qty) throws Exception {
        mockMvc.perform(post("/api/equipment").header("Authorization", bearer())
                .contentType("application/json")
                .content("{\"facilityId\":" + f + ",\"name\":\"" + name + "\",\"model\":\"" + model +
                        "\",\"quantity\":" + qty + ",\"status\":\"CONFIRMED\"}"));
    }

    private long createChat(long facilityId) throws Exception {
        String resp = mockMvc.perform(post("/api/chats").header("Authorization", bearer())
                        .contentType("application/json").content("{\"facilityId\":" + facilityId + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    @Test
    void quantityQuestionAnsweredFromRegistry() throws Exception {
        long f = facility();
        addEquipment(f, "Извещатель пожарный дымовой", "ДИП-34А", 186);
        addEquipment(f, "Извещатель пожарный дымовой", "ИП 212-141", 28);
        addEquipment(f, "Извещатель пожарный ручной", "ИПР 513", 24);
        long chat = createChat(f);

        // сумма только дымовых (186+28=214), ручные не учитываются
        mockMvc.perform(post("/api/chats/" + chat + "/messages").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"question\":\"Сколько дымовых извещателей на объекте?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.containsString("214")))
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.containsString("ДИП-34А")));
    }

    @Test
    void missingInfoAnswerDoesNotInvent() throws Exception {
        long f = facility();
        long chat = createChat(f);
        mockMvc.perform(post("/api/chats/" + chat + "/messages").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"question\":\"Какая марка лифтов установлена?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.containsString("не найден")));
    }

    @Test
    void chatHistoryPersisted() throws Exception {
        long f = facility();
        long chat = createChat(f);
        mockMvc.perform(post("/api/chats/" + chat + "/messages").header("Authorization", bearer())
                .contentType("application/json").content("{\"question\":\"Привет\"}"));

        // история: вопрос пользователя + ответ ассистента
        mockMvc.perform(get("/api/chats/" + chat + "/messages").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].role").value("assistant"));
    }
}
