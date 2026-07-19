package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class DriveIntegrationTest extends IntegrationTestBase {

    private long facility() throws Exception {
        String resp = mockMvc.perform(post("/api/facilities").header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Объект\",\"systems\":[]}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    @Test
    void statusReportsNotConfigured() throws Exception {
        long f = facility();
        mockMvc.perform(get("/api/facilities/" + f + "/drive").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.folderId").doesNotExist());
    }

    @Test
    void setFolderExtractsIdFromLink() throws Exception {
        long f = facility();
        mockMvc.perform(put("/api/facilities/" + f + "/drive/folder").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"folder\":\"https://drive.google.com/drive/folders/1AbC-dEf_GhIjK?usp=sharing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value("1AbC-dEf_GhIjK"));
    }

    @Test
    void setFolderAcceptsRawId() throws Exception {
        long f = facility();
        mockMvc.perform(put("/api/facilities/" + f + "/drive/folder").header("Authorization", bearer())
                        .contentType("application/json").content("{\"folder\":\"raw-folder-id-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value("raw-folder-id-123"));
    }

    @Test
    void syncWithoutServiceAccountReturnsClearError() throws Exception {
        long f = facility();
        mockMvc.perform(put("/api/facilities/" + f + "/drive/folder").header("Authorization", bearer())
                .contentType("application/json").content("{\"folder\":\"some-folder\"}"));
        mockMvc.perform(post("/api/facilities/" + f + "/drive/sync").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
}
