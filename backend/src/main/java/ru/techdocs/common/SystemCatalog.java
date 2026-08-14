package ru.techdocs.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Справочник инженерных систем — константа приложения, общая для всех объектов.
 * Система не заводится произвольным текстом внутри объекта: свободные имена дали
 * «апс» и «Пожарная сигнализация» как две разные системы, и реестр оборудования
 * раздвоился. Любое входящее имя (создание, импорт из файла, папка Drive, раздел
 * эталона, Midio) приводится к стандартному названию отсюда.
 */
public final class SystemCatalog {

    private SystemCatalog() {}

    /** Канонический токен → стандартное отображаемое название. Порядок — порядок в списках. */
    private static final Map<String, String> STANDARD = new LinkedHashMap<>();

    static {
        STANDARD.put("апс", "Пожарная сигнализация");
        STANDARD.put("соуэ", "СОУЭ");
        STANDARD.put("ос", "Охранная сигнализация");
        STANDARD.put("скуд", "СКУД");
        STANDARD.put("видеонаблюдение", "Видеонаблюдение");
        STANDARD.put("домофония", "Домофония");
        STANDARD.put("аду", "Дымоудаление (АДУ)");
        STANDARD.put("аов", "АОВ");
        STANDARD.put("вентиляция", "Вентиляция");
        STANDARD.put("кондиционирование", "Кондиционирование");
        STANDARD.put("отопление", "Отопление");
        STANDARD.put("итп", "ИТП");
        STANDARD.put("водоснабжение", "Водоснабжение");
        STANDARD.put("канализация", "Канализация");
        STANDARD.put("холодоснабжение", "Холодоснабжение");
        STANDARD.put("пнс", "ПНС");
        STANDARD.put("кнс", "КНС");
        STANDARD.put("лифты", "Лифты");
        STANDARD.put("аскуэ", "Энергоучёт");
        STANDARD.put("диспетчеризация", "Диспетчеризация");
    }

    /** Все стандартные названия — то, из чего выбирают при создании системы. */
    public static List<String> names() {
        return List.copyOf(STANDARD.values());
    }

    /**
     * Стандартное название для произвольного написания («апс», «АПС», «Пожарная
     * сигнализация», «СИСТЕМА АВТОМАТИЧЕСКОЙ ПОЖАРНОЙ СИГНАЛИЗАЦИИ» → «Пожарная
     * сигнализация»). null — система в справочнике не распознана.
     */
    public static String standardName(String raw) {
        String token = SystemNormalizer.recognized(raw);
        return token == null ? null : STANDARD.get(token);
    }
}
