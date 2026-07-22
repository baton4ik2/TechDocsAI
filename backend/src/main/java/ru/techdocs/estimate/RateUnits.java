package ru.techdocs.estimate;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор измерителя расценки в числовой объём (колонка M сметы).
 * «10 шт.» → 10, «100 м» → 100, «шт.»/«прибор» → 1.
 */
public final class RateUnits {

    private static final Pattern LEADING_NUMBER = Pattern.compile("(\\d[\\d ]*[.,]?\\d*)");

    private RateUnits() {}

    public static BigDecimal basis(String unit) {
        if (unit == null || unit.isBlank()) return BigDecimal.ONE;
        Matcher m = LEADING_NUMBER.matcher(unit.strip());
        if (m.lookingAt()) {
            String num = m.group(1).replace(" ", "").replace(',', '.');
            try {
                BigDecimal v = new BigDecimal(num);
                return v.signum() > 0 ? v : BigDecimal.ONE;
            } catch (NumberFormatException ignored) {
                return BigDecimal.ONE;
            }
        }
        return BigDecimal.ONE;
    }
}
