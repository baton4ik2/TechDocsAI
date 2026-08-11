package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.midio.MidioMapping;

import static org.assertj.core.api.Assertions.assertThat;

/** Перевод понятий Midio в наши: имя/модель, периодичность, вид и обязательность работы. */
class MidioMappingTest {

    @Test
    void splitsNameAndModelPackedInOneField() {
        var r = MidioMapping.splitModel("Извещатель..ИП 212-64-R3");
        assertThat(r.name()).isEqualTo("Извещатель");
        assertThat(r.model()).isEqualTo("ИП 212-64-R3");
    }

    @Test
    void modelWithoutSeparatorStaysModel() {
        var r = MidioMapping.splitModel("SKAT BC 72/18S RACK");
        assertThat(r.name()).isNull();
        assertThat(r.model()).isEqualTo("SKAT BC 72/18S RACK");
    }

    @Test
    void monthlyRecurrenceBecomesTwelvePerYear() {
        var r = MidioMapping.recurrence(1, "MONTH");
        assertThat(r.perYear()).isEqualByComparingTo("12");
        assertThat(r.text()).isEqualTo("раз в 1 мес.");
    }

    @Test
    void halfYearRecurrenceBecomesTwoPerYear() {
        var r = MidioMapping.recurrence(6, "MONTH");
        assertThat(r.perYear()).isEqualByComparingTo("2");
        assertThat(r.text()).isEqualTo("раз в 6 мес.");
    }

    @Test
    void rareRecurrenceKeepsFractionalValue() {
        // «раз в 2 года» словаря периодичностей нет — важно не потерять само число
        var r = MidioMapping.recurrence(2, "YEAR");
        assertThat(r.perYear()).isEqualByComparingTo("0.5");
        assertThat(r.text()).isEqualTo("раз в 2 года");
    }

    @Test
    void tenYearRecurrenceIsWordedCorrectly() {
        assertThat(MidioMapping.recurrence(10, "YEAR").text()).isEqualTo("раз в 10 лет");
        assertThat(MidioMapping.recurrence(5, "YEAR").text()).isEqualTo("раз в 5 лет");
        assertThat(MidioMapping.recurrence(1, "YEAR").text()).isEqualTo("раз в год");
    }

    @Test
    void unknownRecurrenceIsNotInvented() {
        assertThat(MidioMapping.recurrence(null, "MONTH").perYear()).isNull();
        assertThat(MidioMapping.recurrence(3, null).perYear()).isNull();
        assertThat(MidioMapping.recurrence(0, "MONTH").perYear()).isNull();
    }

    @Test
    void engineeringSystemTypesMapToOurSystems() {
        assertThat(MidioMapping.systemName("FIRE_SAFETY_SYSTEM")).isEqualTo("Пожарная сигнализация");
        assertThat(MidioMapping.systemName("SKUD_ACCESS_POINT")).isEqualTo("СКУД");
        assertThat(MidioMapping.systemName("CAMERA")).isEqualTo("Видеонаблюдение");
        // SENSOR — признак диспетчеризации, а не инженерная система
        assertThat(MidioMapping.systemName("SENSOR")).isNull();
        assertThat(MidioMapping.systemName(null)).isNull();
    }

    @Test
    void mappedSystemsSurviveOurCanonicalization() {
        assertThat(ru.techdocs.common.SystemNormalizer.recognized(
                MidioMapping.systemName("FIRE_SAFETY_SYSTEM"))).isEqualTo("апс");
        assertThat(ru.techdocs.common.SystemNormalizer.recognized(
                MidioMapping.systemName("SKUD_ACCESS_POINT"))).isEqualTo("скуд");
    }

    @Test
    void categoryTellsMandatoryFromRecommended() {
        assertThat(MidioMapping.mandatory("MANDATORY")).isTrue();
        assertThat(MidioMapping.mandatory("RECOMMENDED")).isFalse();
        assertThat(MidioMapping.mandatory("СТРАННОЕ")).isNull();
        assertThat(MidioMapping.mandatory(null)).isNull();
    }

    @Test
    void workTypeComesFromTitleNotFromCategory() {
        assertThat(MidioMapping.workType("Внешний осмотр")).isEqualTo("осмотр");
        assertThat(MidioMapping.workType("Техническое обслуживание")).isEqualTo("ТО");
        assertThat(MidioMapping.workType("Продувка оптической системы")).isEqualTo("чистка");
        assertThat(MidioMapping.workType("Нечто своё")).isNull();
    }
}
