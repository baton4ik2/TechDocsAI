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
    private final String visionModel;
    private final boolean configured;

    public AiClient(AppProperties props) {
        String baseUrl = props.ai().baseUrl();
        String apiKey = props.ai().apiKey();
        this.model = props.ai().chatModel();
        this.visionModel = props.ai().visionModel();
        this.configured = apiKey != null && !apiKey.isBlank()
                || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
                || baseUrl.contains("ollama");

        // локальные модели (Ollama) на CPU могут отвечать очень долго — щедрый таймаут чтения
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(600_000);

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

    public boolean hasVisionModel() {
        return configured && visionModel != null && !visionModel.isBlank();
    }

    /** Запрос к vision-модели: текстовый промпт + изображение страницы (PNG). */
    public String completeVision(String systemPrompt, String userPrompt, byte[] pngImage) {
        String dataUri = "data:image/png;base64," +
                java.util.Base64.getEncoder().encodeToString(pngImage);
        Map<String, Object> body = Map.of(
                "model", visionModel,
                "temperature", 0.1,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", userPrompt),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUri))
                        ))
                )
        );
        return execute(body);
    }

    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.1,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        return execute(body);
    }

    @SuppressWarnings("unchecked")
    private String execute(Map<String, Object> body) {
        try {
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
