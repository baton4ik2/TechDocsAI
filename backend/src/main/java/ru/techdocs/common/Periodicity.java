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

        // «Один разв шесть месяцев» — частая опечатка в регламентах (пропущен пробел)
        s = s.replaceAll("(?<!\\p{L})разв(?=\\s)", "раз в");
        // числительные словами → цифры: «Один раз в шесть месяцев» → «1 раз в 6 месяцев»
        s = numeralsToDigits(s);

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

    /**
     * Единый словарь периодичности для сметы: «раз в 1 мес.» / «раз в 3 мес.» /
     * «раз в 6 мес.» / «раз в год». Формулировки эталонов и паспортов («Ежемесячно»,
     * «Раз в полгода», «Два раза в год») приводятся к нему, чтобы в одном документе
     * не соседствовали разные написания одного и того же. Нераспознанное возвращается
     * как есть — терять текст нельзя.
     */
    public static String canonicalLabel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        BigDecimal py = perYear(raw);
        String canonical = py == null ? null : byPerYear(py);
        return canonical != null ? canonical : raw.strip();
    }

    /** Подпись периодичности по числу выполнений в год (null — если не типовое). */
    public static String byPerYear(BigDecimal perYear) {
        if (perYear == null || perYear.signum() <= 0) return null;
        if (perYear.compareTo(bd(365)) == 0) return "ежедневно";
        if (perYear.compareTo(bd(52)) == 0) return "еженедельно";
        if (perYear.compareTo(BigDecimal.ONE) == 0) return "раз в год";
        // 12 / N месяцев — только когда делится нацело: 12→1, 6→2, 4→3, 3→4, 2→6
        for (int months : new int[]{1, 2, 3, 4, 6}) {
            if (perYear.compareTo(bd(12 / months)) == 0) return "раз в " + months + " мес.";
        }
        return null;
    }

    /**
     * Подпись периодичности для строки сметы: словарь + пояснение, если фактических
     * операций меньше, чем следует из периодичности. Так снимается противоречие
     * «раз в 6 мес., а операций 1»: часть осмотров поглощена более редким ТО
     * (Сборник 25, п. 7.3) — это норма, но в документе это должно быть написано.
     */
    public static String label(String raw, BigDecimal actualPerYear) {
        String base = canonicalLabel(raw);
        if (base == null || actualPerYear == null || actualPerYear.signum() <= 0) return base;
        BigDecimal implied = perYear(base);
        if (implied == null || implied.compareTo(actualPerYear) <= 0) return base;
        BigDecimal absorbed = implied.subtract(actualPerYear).stripTrailingZeros();
        return base + (absorbed.compareTo(BigDecimal.ONE) == 0
                ? " (1 операция совмещена с более редким ТО)"
                : " (" + absorbed.toPlainString() + " " + operations(absorbed) + " совмещены с более редким ТО)");
    }

    /** Согласование слова «операция» с числом: 2–4 операции, 5+ операций. */
    private static String operations(BigDecimal count) {
        if (count.stripTrailingZeros().scale() > 0) return "операции";   // дробное — «1.5 операции»
        long n = count.longValue() % 100;
        if (n >= 11 && n <= 14) return "операций";
        return switch ((int) (n % 10)) {
            case 2, 3, 4 -> "операции";
            default -> "операций";
        };
    }

    /** Числительные словами → цифры (только целые слова, чтобы не портить другие). */
    private static String numeralsToDigits(String s) {
        String[][] numerals = {
                {"двенадцать", "12"}, {"одиннадцать", "11"}, {"десять", "10"}, {"девять", "9"},
                {"восемь", "8"}, {"семь", "7"}, {"шесть", "6"}, {"пять", "5"},
                {"четыре", "4"}, {"три", "3"}, {"два", "2"}, {"две", "2"},
                {"дважды", "2 раза"}, {"один", "1"}, {"одну", "1"}, {"однократно", "1 раз в год"},
        };
        String out = s;
        for (String[] n : numerals) {
            out = out.replaceAll("(?<!\\p{L})" + n[0] + "(?!\\p{L})", n[1]);
        }
        return out;
    }

    private static boolean contains(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    private static BigDecimal bd(int v) {
        return BigDecimal.valueOf(v);
    }
}
