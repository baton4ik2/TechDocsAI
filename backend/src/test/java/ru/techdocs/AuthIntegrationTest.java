package ru.techdocs;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void loginSucceedsWithSeededAdmin() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"admin@techdocs.local\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.user.email").value("admin@techdocs.local"));
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"admin@techdocs.local\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/facilities"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsGarbageToken() throws Exception {
        mockMvc.perform(get("/api/facilities").header("Authorization", "Bearer nonsense"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointAcceptsValidToken() throws Exception {
        mockMvc.perform(get("/api/facilities").header("Authorization", bearer()))
                .andExpect(status().isOk());
    }
}
