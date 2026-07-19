package ru.techdocs.ai;

/**
 * Чистка артефактов OCR в извлечённых полях: обрезка символов таблиц (| _ и т.п.)
 * и починка слов со смешанными алфавитами — «LPA-6С» (кириллическая С) → «LPA-6C»,
 * латинские подмены в «ОПОП» → кириллица.
 */
public final class OcrTextCleaner {

    // Латиница ↔ кириллица: пары букв, неотличимые визуально (гомоглифы).
    private static final String LATIN_LOOKALIKES = "ABCEHKMOPTXYaceopxy";
    private static final String CYRILLIC_LOOKALIKES = "АВСЕНКМОРТХУасеорху";

    private OcrTextCleaner() {}

    public static String clean(String value) {
        if (value == null) return null;
        String s = value.strip()
                .replaceAll("^[|_\\-–—•.,;:\\s]+", "")
                .replaceAll("[|_•\\s]+$", "")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s*\\|\\s*", " ");
        StringBuilder result = new StringBuilder();
        for (String token : s.split(" ")) {
            if (!result.isEmpty()) result.append(' ');
            result.append(normalizeToken(token));
        }
        return result.toString().strip();
    }

    /** Внутри одного слова приводим буквы-двойники к преобладающему алфавиту. */
    static String normalizeToken(String token) {
        long cyrillic = token.chars().filter(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC).count();
        long latin = token.chars().filter(c -> c < 128 && Character.isLetter(c)).count();
        if (cyrillic == 0 || latin == 0) return token; // алфавиты не смешаны
        boolean toCyrillic = cyrillic >= latin;
        String from = toCyrillic ? LATIN_LOOKALIKES : CYRILLIC_LOOKALIKES;
        String to = toCyrillic ? CYRILLIC_LOOKALIKES : LATIN_LOOKALIKES;
        StringBuilder sb = new StringBuilder(token.length());
        for (char c : token.toCharArray()) {
            int idx = from.indexOf(c);
            sb.append(idx >= 0 ? to.charAt(idx) : c);
        }
        return sb.toString();
    }
}
