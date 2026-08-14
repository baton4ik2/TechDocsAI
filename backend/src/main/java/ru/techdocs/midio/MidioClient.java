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

    /**
     * Регламентная работа на стороне Midio. Периодичность приходит и числом:
     * «раз в 2 года» наш парсер текста не понимает, и через строку значение
     * потерялось бы.
     */
    /**
     * dedicatedPlan — работа из плана на ОДНО изделие (или с явной ссылкой на
     * него). Зонные планы привязывают всё оборудование зоны сразу, поэтому свои
     * работы важнее зонных: зонные берутся, только когда своих нет.
     */
    record ExternalWork(String externalId, String equipmentExternalId, String name,
                        String workType, String periodicity, java.math.BigDecimal perYear,
                        String composition, Boolean mandatory, boolean dedicatedPlan,
                        String planName) {}

    /** Настроен ли доступ: без реквизитов синхронизацию не предлагаем. */
    boolean isConfigured();

    List<ExternalEquipment> equipment();

    /** Плановые работы; каждая ссылается на оборудование своим equipmentExternalId. */
    List<ExternalWork> works();
}
