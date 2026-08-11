package ru.techdocs.midio;

import ru.techdocs.common.Periodicity;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Перевод понятий Midio в наши. Вынесено отдельно от HTTP: это чистые правила,
 * которые должны проверяться тестами без обращения к их стенду.
 */
public final class MidioMapping {

    private MidioMapping() {}

    /** Разделитель наименования и модели в карточке оборудования Midio. */
    private static final String NAME_MODEL_SEPARATOR = "..";

    /** Наименование и модель, упакованные в одно поле: «Извещатель..ИП 212-64-R3». */
    public record NameAndModel(String name, String model) {}

    public static NameAndModel splitModel(String raw) {
        if (raw == null || raw.isBlank()) return new NameAndModel(null, null);
        int at = raw.indexOf(NAME_MODEL_SEPARATOR);
        if (at < 0) return new NameAndModel(null, raw.strip());
        String name = raw.substring(0, at).strip();
        String model = raw.substring(at + NAME_MODEL_SEPARATOR.length()).strip();
        // «..ИП 212-64» без наименования и «Извещатель..» без модели одинаково возможны
        return new NameAndModel(name.isBlank() ? null : name, model.isBlank() ? null : model);
    }

    /**
     * Тип инженерной системы Midio → наше название системы. Дальше его канонизирует
     * {@link ru.techdocs.common.SystemNormalizer}, поэтому здесь достаточно
     * человеческого имени, а не токена.
     */
    public static String systemName(String engineeringSystemType) {
        if (engineeringSystemType == null) return null;
        return switch (engineeringSystemType.toUpperCase()) {
            case "FIRE_SAFETY_SYSTEM" -> "Пожарная сигнализация";
            case "SECURITY_SYSTEM" -> "Охранная сигнализация";
            case "SKUD_ACCESS_POINT" -> "СКУД";
            case "CAMERA" -> "Видеонаблюдение";
            case "VENTILATION" -> "Вентиляция";
            case "ELEVATOR" -> "Лифты";
            case "INDIVIDUAL_HEAT_POINT" -> "ИТП";
            // SENSOR — это не инженерная система, а признак диспетчеризации:
            // системы у такого оборудования нет, и выдумывать её нельзя
            default -> null;
        };
    }

    /** Периодичность Midio: интервал + единица. */
    public record Recurrence(BigDecimal perYear, String text) {}

    /**
     * Сколько раз в год выполняется работа. Считаем числом, а не разбором текста:
     * «раз в 2 года» наш парсер периодичности не понимает, и через строку значение
     * потерялось бы.
     */
    public static Recurrence recurrence(Integer interval, String unit) {
        if (interval == null || interval <= 0 || unit == null) return new Recurrence(null, null);
        BigDecimal perYear = switch (unit.toUpperCase()) {
            case "DAY", "DAYS" -> BigDecimal.valueOf(365).divide(BigDecimal.valueOf(interval), 4, RoundingMode.HALF_UP);
            case "WEEK", "WEEKS" -> BigDecimal.valueOf(52).divide(BigDecimal.valueOf(interval), 4, RoundingMode.HALF_UP);
            case "MONTH", "MONTHS" -> BigDecimal.valueOf(12).divide(BigDecimal.valueOf(interval), 4, RoundingMode.HALF_UP);
            case "YEAR", "YEARS" -> BigDecimal.ONE.divide(BigDecimal.valueOf(interval), 4, RoundingMode.HALF_UP);
            default -> null;
        };
        if (perYear == null) return new Recurrence(null, null);
        perYear = perYear.stripTrailingZeros();
        String text = Periodicity.byPerYear(perYear);
        if (text == null) text = fallbackText(interval, unit);
        return new Recurrence(perYear, text);
    }

    /** Подпись для нетиповых периодичностей («раз в 2 года»), которых нет в словаре. */
    private static String fallbackText(int interval, String unit) {
        String noun = switch (unit.toUpperCase()) {
            case "DAY", "DAYS" -> interval == 1 ? "день" : "дн.";
            case "WEEK", "WEEKS" -> interval == 1 ? "неделю" : "нед.";
            case "MONTH", "MONTHS" -> interval == 1 ? "месяц" : "мес.";
            case "YEAR", "YEARS" -> interval == 1 ? "год" : years(interval);
            default -> null;
        };
        if (noun == null) return null;
        return interval == 1 ? "раз в " + noun : "раз в " + interval + " " + noun;
    }

    private static String years(int interval) {
        int last = interval % 10;
        int lastTwo = interval % 100;
        if (lastTwo >= 11 && lastTwo <= 14) return "лет";
        return last >= 2 && last <= 4 ? "года" : "лет";
    }

    /**
     * Категория работы: обязательная или рекомендуемая. Различие денежное —
     * рекомендуемые работы заказчик оплачивать не обязан, и молча смешивать их
     * с обязательными в смете нельзя.
     */
    public static Boolean mandatory(String category) {
        if (category == null || category.isBlank()) return null;
        return switch (category.toUpperCase()) {
            case "MANDATORY" -> Boolean.TRUE;
            case "RECOMMENDED" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * Вид работы по её названию — то же деление, что и в наших плановых работах.
     * Midio категорией называет другое (обязательность), поэтому берём из заголовка.
     */
    public static String workType(String title) {
        if (title == null) return null;
        String t = title.toLowerCase().replace('ё', 'е');
        if (t.contains("осмотр")) return "осмотр";
        if (t.contains("обслуживан")) return "ТО";
        if (t.contains("контроль функционир")) return "контроль функционирования";
        if (t.contains("проверк")) return "проверка";
        if (t.contains("измерен")) return "измерения";
        if (t.contains("чистк") || t.contains("продувк")) return "чистка";
        return null;
    }
}
