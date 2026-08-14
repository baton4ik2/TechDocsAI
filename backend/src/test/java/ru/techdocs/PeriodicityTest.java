package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.common.Periodicity;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodicityTest {

    @Test
    void recognisesCommonPhrasings() {
        assertThat(Periodicity.perYear("Ежемесячно")).isEqualByComparingTo("12");
        assertThat(Periodicity.perYear("раз в 1 мес.")).isEqualByComparingTo("12");
        assertThat(Periodicity.perYear("Ежеквартально")).isEqualByComparingTo("4");
        assertThat(Periodicity.perYear("раз в 3 мес.")).isEqualByComparingTo("4");
        assertThat(Periodicity.perYear("Два раза  в год")).isEqualByComparingTo("2");
        assertThat(Periodicity.perYear("раз в 6 мес.")).isEqualByComparingTo("2");
        assertThat(Periodicity.perYear("полугодовое")).isEqualByComparingTo("2");
        assertThat(Periodicity.perYear("раз в 1 год")).isEqualByComparingTo("1");
        assertThat(Periodicity.perYear("Ежегодно")).isEqualByComparingTo("1");
        assertThat(Periodicity.perYear("4 раза в год")).isEqualByComparingTo("4");
    }

    /** Формулировки из реальных регламентов: числительные словами и опечатка «разв». */
    @Test
    void recognisesRussianNumeralsAndTypos() {
        assertThat(Periodicity.perYear("Один раз в шесть месяцев")).isEqualByComparingTo("2");
        assertThat(Periodicity.perYear("Один разв шесть месяцев")).isEqualByComparingTo("2");
        assertThat(Periodicity.perYear("Один раз в год")).isEqualByComparingTo("1");
        assertThat(Periodicity.perYear("Два раза в год")).isEqualByComparingTo("2");
        assertThat(Periodicity.perYear("Один раз в три месяца")).isEqualByComparingTo("4");
        assertThat(Periodicity.perYear("Дважды в год")).isEqualByComparingTo("2");
    }

    /** Единый словарь: в одном документе не должно быть «Ежемесячно» рядом с «раз в 1 мес.». */
    @Test
    void normalisesToSingleDictionary() {
        assertThat(Periodicity.canonicalLabel("Ежемесячно")).isEqualTo("раз в 1 мес.");
        assertThat(Periodicity.canonicalLabel("раз в месяц")).isEqualTo("раз в 1 мес.");
        assertThat(Periodicity.canonicalLabel("Ежеквартально")).isEqualTo("раз в 3 мес.");
        assertThat(Periodicity.canonicalLabel("Два раза в год")).isEqualTo("раз в 6 мес.");
        assertThat(Periodicity.canonicalLabel("Раз в год")).isEqualTo("раз в год");
        // нераспознанное не теряем
        assertThat(Periodicity.canonicalLabel("по мере необходимости")).isEqualTo("по мере необходимости");
        assertThat(Periodicity.canonicalLabel(null)).isNull();
    }

    /**
     * Поглощение осмотров: периодичность «раз в 6 мес.», а операций 1 — это не ошибка,
     * вторая совмещена с годовым ТО. В документе это должно быть написано.
     */
    @Test
    void explainsAbsorbedOperations() {
        assertThat(Periodicity.label("раз в 6 мес.", new java.math.BigDecimal("1")))
                .isEqualTo("раз в 6 мес. (1 операция совмещена с более редким ТО)");
        assertThat(Periodicity.label("Ежемесячно", new java.math.BigDecimal("10")))
                .isEqualTo("раз в 1 мес. (2 операции совмещены с более редким ТО)");
        // совпадает с периодичностью — пояснение не нужно
        assertThat(Periodicity.label("раз в 6 мес.", new java.math.BigDecimal("2")))
                .isEqualTo("раз в 6 мес.");
        assertThat(Periodicity.label("раз в год", null)).isEqualTo("раз в год");
    }

    @Test
    void returnsNullForUnknownOrBlank() {
        assertThat(Periodicity.perYear(null)).isNull();
        assertThat(Periodicity.perYear("  ")).isNull();
        assertThat(Periodicity.perYear("по мере необходимости")).isNull();
    }
}
