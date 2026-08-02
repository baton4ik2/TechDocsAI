package ru.techdocs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "techdocs")
public record AppProperties(
        Auth auth,
        Storage storage,
        Ai ai,
        Document document,
        Cors cors,
        Drive drive
) {
    public record Auth(String adminEmail, String adminPassword, String jwtSecret, int jwtExpirationMinutes) {}

    public record Storage(String endpoint, String accessKey, String secretKey, String bucket) {}

    public record Ai(String baseUrl, String apiKey, String chatModel,
                     String visionBaseUrl, String visionApiKey, String visionModel,
                     String matchBaseUrl, String matchApiKey, String matchModel,
                     String reviewBaseUrl, String reviewApiKey, String reviewModel,
                     String reviewModels, String reviewExplainModel) {}

    public record Document(int maxFileSizeMb, int maxPages) {}

    public record Cors(String allowedOrigins) {}

    public record Drive(String serviceAccountKeyPath) {}
}
