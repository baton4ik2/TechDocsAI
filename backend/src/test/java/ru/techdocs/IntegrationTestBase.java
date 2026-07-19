package ru.techdocs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * База для интеграционных тестов: полный Spring-контекст + MockMvc + реальный
 * Postgres (профиль test, БД techdocs_test). Даёт валидный JWT через логин.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper json;

    protected String token;

    @BeforeEach
    void login() throws Exception {
        String body = """
                {"email":"admin@techdocs.local","password":"admin123"}
                """;
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        this.token = node.get("token").asText();
    }

    protected String bearer() {
        return "Bearer " + token;
    }
}
