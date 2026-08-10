package ru.techdocs.midio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.techdocs.midio.MidioEquipmentMatcher.ExternalEquipment;

import java.util.List;

@Configuration
public class MidioConfig {

    /**
     * Заглушка на время, пока не подключён живой клиент Midio. Приложение
     * поднимается и работает без интеграции, а попытка синхронизации отвечает
     * «не настроена» — вместо падения контекста при старте.
     */
    @Bean
    @ConditionalOnMissingBean(MidioClient.class)
    public MidioClient midioClientUnavailable() {
        return new MidioClient() {
            @Override public boolean isConfigured() { return false; }
            @Override public List<ExternalEquipment> equipment() { return List.of(); }
            @Override public List<ExternalWork> works() { return List.of(); }
        };
    }
}
