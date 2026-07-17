package ru.techdocs.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.techdocs.config.AppProperties;

import java.util.List;
import java.util.Map;

/**
 * Клиент OpenAI-совместимого API (OpenAI, Ollama, LM Studio и т.д.).
 * Если ключ не задан и это не локальный сервер — ИИ отключён,
 * и чат работает в экстрактивном режиме (цитаты из документов).
 */
@Service
@Slf4j
public class AiClient {

    private final RestClient restClient;
    private final String model;
    private final boolean configured;

    public AiClient(AppProperties props) {
        String baseUrl = props.ai().baseUrl();
        String apiKey = props.ai().apiKey();
        this.model = props.ai().chatModel();
        this.configured = apiKey != null && !apiKey.isBlank()
                || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
                || baseUrl.contains("ollama");

        // локальные модели (Ollama) могут отвечать долго — щедрый таймаут чтения
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(180_000);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
    }

    public boolean isConfigured() {
        return configured;
    }

    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0.1,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );
            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) return null;
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<String, Object> message = (Map<String, Object>) choices.getFirst().get("message");
            return message == null ? null : (String) message.get("content");
        } catch (Exception e) {
            log.warn("Запрос к ИИ-провайдеру не удался: {}", e.getMessage());
            return null;
        }
    }
}
