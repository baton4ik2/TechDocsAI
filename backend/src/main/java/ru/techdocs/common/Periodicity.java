package ru.techdocs.common;

import java.math.BigDecimal;

/**
 * Нормализация периодичности обслуживания в число выполнений в год.
 * Понимает формулировки регламентов и паспортов: «Ежемесячно», «Два раза в год»,
 * «раз в 6 мес.», «ежеквартально» и т.п. Используется и в ПКМ, и при расчёте смет.
 */
public final class Periodicity {

    private Periodicity() {}

    /** Число выполнений в год или null, если распознать не удалось. */
    public static BigDecimal perYear(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("\\s+", " ")
                .strip();
        if (s.isEmpty()) return null;

        // явное «N раз(а) в год»
        var m = java.util.regex.Pattern.compile("(\\d+)\\s*раз\\p{L}*\\s+в\\s+год").matcher(s);
        if (m.find()) return bd(Integer.parseInt(m.group(1)));

        // «раз в N мес.» → 12 / N
        m = java.util.regex.Pattern.compile("раз\\p{L}*\\s+в\\s+(\\d+)\\s*мес").matcher(s);
        if (m.find()) {
            int months = Integer.parseInt(m.group(1));
            return months > 0 ? BigDecimal.valueOf(12).divide(bd(months), 2, java.math.RoundingMode.HALF_UP) : null;
        }
        // «раз в N год(а)» → 1 / N
        m = java.util.regex.Pattern.compile("раз\\p{L}*\\s+в\\s+(\\d+)\\s*год").matcher(s);
        if (m.find()) {
            int years = Integer.parseInt(m.group(1));
            return years > 0 ? BigDecimal.ONE.divide(bd(years), 2, java.math.RoundingMode.HALF_UP) : null;
        }

        if (contains(s, "ежедневн")) return bd(365);
        if (contains(s, "еженедельн")) return bd(52);
        if (contains(s, "ежемесячн", "раз в месяц", "1 раз в месяц")) return bd(12);
        if (contains(s, "ежекварт", "раз в квартал", "поквартальн")) return bd(4);
        if (contains(s, "два раза в год", "2 раза в год", "дважды в год",
                "раз в полгода", "полугодов", "раз в 6 мес")) return bd(2);
        if (contains(s, "ежегодн", "раз в год", "один раз в год", "1 раз в год", "годов")) return bd(1);
        return null;
    }

    private static boolean contains(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    private static BigDecimal bd(int v) {
        return BigDecimal.valueOf(v);
    }
}
