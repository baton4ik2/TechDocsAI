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

    @Test
    void returnsNullForUnknownOrBlank() {
        assertThat(Periodicity.perYear(null)).isNull();
        assertThat(Periodicity.perYear("  ")).isNull();
        assertThat(Periodicity.perYear("по мере необходимости")).isNull();
    }
}
