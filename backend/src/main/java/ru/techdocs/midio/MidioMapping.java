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
     * Каким изделиям плана принадлежит работа. Работа в Midio не несёт ссылки на
     * оборудование — только план знает свой список изделий. Но название работы
     * обычно называет модель («ТО извещателя теплового ИП 101-29-PR-R3»), и по
     * ней работу можно отдать своему изделию, а не всем подряд: иначе тепловой
     * извещатель получил бы ТО ручного, дымового и линейного из того же плана.
     * <p>
     * Модель не распознана или распознана неоднозначно — работа общая, идёт всем
     * изделиям плана («Проверка функционирования системы»).
     */
    public static java.util.List<String> workTargets(String title, java.util.List<String> equipmentIds,
                                                     java.util.Map<String, String> modelsById) {
        if (equipmentIds.size() <= 1) return equipmentIds;
        String titleKey = ru.techdocs.common.ModelMatching.modelKey(title);
        java.util.List<String> strong = new java.util.ArrayList<>();
        java.util.List<String> weak = new java.util.ArrayList<>();
        for (String id : equipmentIds) {
            String key = ru.techdocs.common.ModelMatching.modelKey(modelsById.getOrDefault(id, ""));
            if (key.length() < 3) continue;
            if (titleKey.contains(key)) {
                strong.add(id);
                continue;
            }
            // модели пишут по-разному («264/1» в работе, «264.1-100» в карточке):
            // хватает букв изделия и первой числовой группы — «ипдл264»
            String needle = letterAndFirstDigits(modelsById.get(id));
            if (needle != null && needle.length() >= 3 && titleKey.contains(needle)) weak.add(id);
        }
        if (!strong.isEmpty()) return strong;
        if (weak.size() == 1) return weak;
        return equipmentIds;
    }

    /**
     * Префикс сырой модели до конца первой числовой группы: «ИПДЛ-264.1-100» →
     * «ипдл264». Считать по нормализованному ключу нельзя — он склеивает цифры
     * подряд, и «первая группа» захватила бы всё («2641100»).
     */
    private static String letterAndFirstDigits(String rawModel) {
        if (rawModel == null) return null;
        var m = java.util.regex.Pattern.compile("^[^0-9]*[0-9]+").matcher(rawModel);
        return m.find() ? ru.techdocs.common.ModelMatching.modelKey(m.group()) : null;
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
