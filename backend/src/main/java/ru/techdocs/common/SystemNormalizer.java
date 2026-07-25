package ru.techdocs.common;

/**
 * Канонизация названия инженерной системы к общему токену. Приводит к одному
 * значению и короткие названия объектов («СКУД», «Видеонаблюдение»), и развёрнутые
 * заголовки разделов эталона («СИСТЕМА КОНТРОЛЯ И УПРАВЛЕНИЯ ДОСТУПОМ»), чтобы одно
 * и то же оборудование в одной системе совпадало между объектом и эталоном.
 */
public final class SystemNormalizer {

    private SystemNormalizer() {}

    /**
     * Канонический токен системы для ключа идентичности оборудования. Для известных
     * систем — общий токен (скуд, апс, …); для неизвестных — «сжатое» имя (буквы/цифры),
     * чтобы ключ всё равно был стабильным. null/пусто → null.
     */
    public static String canonical(String system) {
        String known = recognized(system);
        if (known != null) return known;
        if (system == null) return null;
        String compact = system.toLowerCase().replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
        return compact.isBlank() ? null : compact;
    }

    /**
     * Токен только для распознанной инженерной системы, иначе null. Используется при
     * разборе эталона: строку-раздел распознаём как систему лишь когда это реально
     * известная система (а не «ИТОГО» и не служебная строка).
     */
    public static String recognized(String system) {
        if (system == null) return null;
        String s = system.toLowerCase().replace('ё', 'е');
        String compact = s.replaceAll("[^\\p{L}\\p{N}]", "");
        if (compact.isBlank()) return null;

        if (has(s, "контрол", "доступ") || compact.contains("скуд") || compact.contains("ктсо")) return "скуд";
        if (s.contains("домофон")) return "домофония";
        if (s.contains("видеонаблюд") || compact.contains("видеонаблюдение")) return "видеонаблюдение";
        if (s.contains("оповещ") || compact.contains("соуэ") || compact.contains("аоу")) return "соуэ";
        if (has(s, "пожарн", "сигнализ") || compact.contains("апс") || compact.contains("спс")
                || compact.contains("аупс")) return "апс";
        if (has(s, "охранн", "сигнализ") || compact.equals("ос")) return "ос";
        if (s.contains("вентиляц")) return "вентиляция";
        if (s.contains("отоплен")) return "отопление";
        if (s.contains("холодоснаб")) return "холодоснабжение";
        if (s.contains("водоснаб")) return "водоснабжение";
        if (s.contains("канализац")) return "канализация";
        if (s.contains("лифт")) return "лифты";
        if (s.contains("итп") || s.contains("тепловойпункт")) return "итп";
        if (s.contains("энергоучет") || s.contains("аскуэ") || has(s, "учет", "энергоресурс")) return "аскуэ";
        if (s.contains("диспетчериз")) return "диспетчеризация";
        return null;
    }

    private static boolean has(String s, String... needles) {
        for (String n : needles) if (!s.contains(n)) return false;
        return true;
    }
}
