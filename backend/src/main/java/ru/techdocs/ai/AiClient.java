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
    private final RestClient visionRestClient;
    private final RestClient matchRestClient;
    private final RestClient reviewRestClient;
    private final String model;
    private final String visionModel;
    private final String matchModel;
    private final String reviewModel;
    private final String passportModel;
    private final boolean configured;
    private final boolean visionConfigured;
    private final boolean matchConfigured;
    private final boolean reviewConfigured;

    public AiClient(AppProperties props) {
        String baseUrl = props.ai().baseUrl();
        String apiKey = props.ai().apiKey();
        this.model = props.ai().chatModel();
        this.visionModel = props.ai().visionModel();
        this.configured = isUsable(baseUrl, apiKey);
        this.restClient = buildClient(baseUrl, apiKey);

        // vision может ходить к другому провайдеру (например, чат — локальный Ollama,
        // извлечение — облачный Gemini). Пустой vision-base-url = основной провайдер.
        String visionBaseUrl = props.ai().visionBaseUrl();
        String visionApiKey = props.ai().visionApiKey();
        if (visionBaseUrl != null && !visionBaseUrl.isBlank()) {
            this.visionRestClient = buildClient(visionBaseUrl, visionApiKey);
            this.visionConfigured = isUsable(visionBaseUrl, visionApiKey);
        } else {
            this.visionRestClient = this.restClient;
            this.visionConfigured = this.configured;
        }

        // подбор расценок в сметах: отдельный текстовый провайдер (например, облачный
        // Gemini 2.5). Пустой match-base-url = основной провайдер.
        this.matchModel = props.ai().matchModel();
        String matchBaseUrl = props.ai().matchBaseUrl();
        String matchApiKey = props.ai().matchApiKey();
        if (matchBaseUrl != null && !matchBaseUrl.isBlank()) {
            this.matchRestClient = buildClient(matchBaseUrl, matchApiKey);
            this.matchConfigured = isUsable(matchBaseUrl, matchApiKey);
        } else {
            this.matchRestClient = this.restClient;
            this.matchConfigured = this.configured;
        }

        // проверка готовой сметы: рассуждение по длинному контексту, поэтому модель
        // здесь сильнее, чем для подбора расценок, и меняется независимо от него
        this.reviewModel = props.ai().reviewModel();
        String reviewBaseUrl = props.ai().reviewBaseUrl();
        String reviewApiKey = props.ai().reviewApiKey();
        if (reviewBaseUrl != null && !reviewBaseUrl.isBlank()) {
            this.reviewRestClient = buildClient(reviewBaseUrl, reviewApiKey);
            this.reviewConfigured = isUsable(reviewBaseUrl, reviewApiKey);
        } else {
            this.reviewRestClient = this.restClient;
            this.reviewConfigured = this.configured;
        }

        // разбор паспорта — извлечение фактов из короткого текста, задача дешёвая:
        // отдельного провайдера не заводим, ходим через match-провайдер, меняя модель
        this.passportModel = props.ai().passportModel();
    }

    private static boolean isUsable(String baseUrl, String apiKey) {
        return (apiKey != null && !apiKey.isBlank())
                || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
                || baseUrl.contains("ollama");
    }

    private static RestClient buildClient(String baseUrl, String apiKey) {
        // локальные модели (Ollama) на CPU могут отвечать очень долго — щедрый таймаут чтения
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(600_000);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    public boolean isConfigured() {
        return configured;
    }

    public boolean hasVisionModel() {
        return visionConfigured && visionModel != null && !visionModel.isBlank();
    }

    /** Запрос к vision-модели: текстовый промпт + изображение страницы (PNG). */
    public String completeVision(String systemPrompt, String userPrompt, byte[] pngImage) {
        return completeVision(systemPrompt, userPrompt, pngImage, null);
    }

    /** То же с явным выбором vision-модели (провайдер прежний). */
    public String completeVision(String systemPrompt, String userPrompt, byte[] pngImage, String model) {
        String chosen = model == null || model.isBlank() ? visionModel : model.strip();
        String dataUri = "data:image/png;base64," +
                java.util.Base64.getEncoder().encodeToString(pngImage);
        Map<String, Object> body = Map.of(
                "model", chosen,
                "temperature", 0.1,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", userPrompt),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUri))
                        ))
                )
        );
        return execute(visionRestClient, body);
    }

    public String complete(String systemPrompt, String userPrompt) {
        return complete(restClient, model, systemPrompt, userPrompt);
    }

    public boolean hasMatchModel() {
        return matchConfigured && matchModel != null && !matchModel.isBlank();
    }

    /** Запрос к модели подбора расценок (текстовый, отдельный провайдер для смет). */
    public String completeMatch(String systemPrompt, String userPrompt) {
        return complete(matchRestClient, matchModel, systemPrompt, userPrompt);
    }

    public boolean hasReviewModel() {
        return reviewConfigured && reviewModel != null && !reviewModel.isBlank();
    }

    /** Запрос к модели проверки сметы (отдельный провайдер, сильнее match-модели). */
    public String completeReview(String systemPrompt, String userPrompt) {
        return completeReview(systemPrompt, userPrompt, null);
    }

    /**
     * То же с явным выбором модели — чтобы сравнивать модели на одной смете, не
     * пересобирая контейнер. Пустое значение означает модель по умолчанию.
     */
    public String completeReview(String systemPrompt, String userPrompt, String model) {
        String chosen = model == null || model.isBlank() ? reviewModel : model.strip();
        return complete(reviewRestClient, chosen, systemPrompt, userPrompt);
    }

    /** Модель проверки по умолчанию — она же единственная, если список не задан. */
    public String defaultReviewModel() {
        return reviewModel;
    }

    /**
     * Разбор паспорта оборудования. Провайдер — тот же, что у подбора расценок,
     * потому что модель здесь тоже дешёвая и задача та же по природе: вытащить
     * факты из короткого текста. Пустая модель — значение по умолчанию.
     */
    public String completePassport(String systemPrompt, String userPrompt, String model) {
        String chosen = model == null || model.isBlank() ? defaultPassportModel() : model.strip();
        if (chosen == null || chosen.isBlank()) return null;
        return complete(hasMatchModel() ? matchRestClient : restClient, chosen, systemPrompt, userPrompt);
    }

    /** Модель для паспортов по умолчанию: своя, иначе match-модель, иначе чатовая. */
    public String defaultPassportModel() {
        if (passportModel != null && !passportModel.isBlank()) return passportModel.strip();
        return hasMatchModel() ? matchModel : model;
    }

    public boolean hasPassportModel() {
        String chosen = defaultPassportModel();
        if (chosen == null || chosen.isBlank()) return false;
        return hasMatchModel() || configured;
    }

    private String complete(RestClient client, String model, String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.1,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        return execute(client, body);
    }

    @SuppressWarnings("unchecked")
    private String execute(RestClient client, Map<String, Object> body) {
        try {
            Map<String, Object> response = client.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                log.warn("Запрос к ИИ-провайдеру не удался: пустой ответ (модель '{}')", body.get("model"));
                return null;
            }
            // OpenAI-совместимые провайдеры (в т.ч. routerai) часто отдают ошибку телом
            // со статусом 200 — напр. {"error":"Model '…' not found"}. Раньше это молча
            // превращалось в null → тихий фолбэк. Теперь ошибка видна в логах.
            if (response.get("error") != null) {
                log.warn("Запрос к ИИ-провайдеру не удался (модель '{}'): {}",
                        body.get("model"), response.get("error"));
                return null;
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("Запрос к ИИ-провайдеру не удался: в ответе нет choices (модель '{}'): {}",
                        body.get("model"), response);
                return null;
            }
            Map<String, Object> message = (Map<String, Object>) choices.getFirst().get("message");
            return message == null ? null : (String) message.get("content");
        } catch (Exception e) {
            log.warn("Запрос к ИИ-провайдеру не удался (модель '{}'): {}", body.get("model"), e.getMessage());
            return null;
        }
    }
}
