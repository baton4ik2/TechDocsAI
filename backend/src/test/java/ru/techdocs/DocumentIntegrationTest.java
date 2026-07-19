package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class DocumentIntegrationTest extends IntegrationTestBase {

    private long facility() throws Exception {
        String resp = mockMvc.perform(post("/api/facilities").header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Объект\",\"systems\":[]}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    @Test
    void documentTypesSeeded() throws Exception {
        mockMvc.perform(get("/api/documents/types").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(13)); // 13 типов из сид-данных
    }

    @Test
    void uploadRejectsUnsupportedFormat() throws Exception {
        long f = facility();
        MockMultipartFile bad = new MockMultipartFile("files", "virus.exe",
                "application/octet-stream", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/documents/upload").file(bad)
                        .param("facilityId", String.valueOf(f))
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadTxtCreatesDocument() throws Exception {
        long f = facility();
        // ASCII-имя: MockMvc манглит кириллицу в multipart (реальный UTF-8 upload работает)
        MockMultipartFile txt = new MockMultipartFile("files", "passport.txt",
                "text/plain", "Назначение прибора.".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/documents/upload").file(txt)
                        .param("facilityId", String.valueOf(f))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].originalFilename").value("passport.txt"))
                .andExpect(jsonPath("$[0].facilityId").value(f));

        mockMvc.perform(get("/api/documents?facilityId=" + f).header("Authorization", bearer()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void deleteDocument() throws Exception {
        long f = facility();
        MockMultipartFile txt = new MockMultipartFile("files", "doc.txt",
                "text/plain", "текст".getBytes(StandardCharsets.UTF_8));
        String resp = mockMvc.perform(multipart("/api/documents/upload").file(txt)
                        .param("facilityId", String.valueOf(f))
                        .header("Authorization", bearer()))
                .andReturn().getResponse().getContentAsString();
        long docId = json.readTree(resp).get(0).get("id").asLong();

        mockMvc.perform(delete("/api/documents/" + docId).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
    }
}
