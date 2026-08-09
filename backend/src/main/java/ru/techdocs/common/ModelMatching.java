package ru.techdocs.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Сравнение моделей оборудования из разных источников: эталона, реестра, внешних
 * систем. Одно место на всё приложение — правила должны совпадать, иначе подбор
 * расценки и привязка внешних плановых работ разойдутся на одном и том же изделии.
 *
 * <p>Одной похожести строк мало: «РМ-4К» одинаково близок и к «РМ-1К», и к «РМ-4»
 * (обе — одна правка символа). Изделие различает НОМЕР в модели, поэтому
 * {@link #digits(String)} используется как разводящий признак при равной похожести.
 */
public final class ModelMatching {

    /** Схожесть моделей, при которой считаем, что это одно изделие. */
    public static final double THRESHOLD = 0.75;

    /** Служебные части модели, не различающие изделия: маркер протокола Рубеж. */
    private static final Set<String> MODEL_NOISE = Set.of("прот", "r3", "р3");

    private ModelMatching() {}

    /**
     * Нормализация модели: регистр, ё→е, похожие кириллические буквы → латиница,
     * только буквы/цифры. Маркер протокола отбрасывается — «РМ-4-R3» на объекте и
     * «РМ-4 прот. R3» в эталоне это одно изделие, а «РМ-1 прот. R3» — другое.
     */
    public static String modelKey(String model) {
        if (model == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String token : model.toLowerCase().replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (token.isBlank() || MODEL_NOISE.contains(token)) continue;
            for (char c : token.toCharArray()) {
                sb.append(switch (c) {     // визуально одинаковые кириллица/латиница
                    case 'х' -> 'x'; case 'а' -> 'a'; case 'е' -> 'e'; case 'о' -> 'o';
                    case 'р' -> 'p'; case 'с' -> 'c'; case 'у' -> 'y'; case 'к' -> 'k';
                    default -> c;
                });
            }
        }
        return sb.toString();
    }

    /** Числовые группы модели: «рм4к» → [4], «ивэпр122x7» → [122, 7]. */
    public static List<String> digits(String modelKey) {
        List<String> out = new ArrayList<>();
        var m = java.util.regex.Pattern.compile("\\d+").matcher(modelKey);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Схожесть строк 0..1 по расстоянию Левенштейна. */
    public static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 0;
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }
}
