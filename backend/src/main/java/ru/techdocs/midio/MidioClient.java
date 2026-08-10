package ru.techdocs.midio;

import ru.techdocs.midio.MidioEquipmentMatcher.ExternalEquipment;

import java.util.List;

/**
 * Доступ к данным Midio. Отделён от синхронизации намеренно: как именно
 * запрашиваются регламенты (эндпоинты, авторизация, формат ответа) — вопрос
 * их API, а что делать с полученным — наш. Подмена реализации даёт тесты
 * синхронизации без сети.
 */
public interface MidioClient {

    /** Регламентная работа на стороне Midio. */
    record ExternalWork(String externalId, String equipmentExternalId, String name,
                        String workType, String periodicity, String composition) {}

    /** Настроен ли доступ: без реквизитов синхронизацию не предлагаем. */
    boolean isConfigured();

    List<ExternalEquipment> equipment();

    /** Плановые работы; каждая ссылается на оборудование своим equipmentExternalId. */
    List<ExternalWork> works();
}
