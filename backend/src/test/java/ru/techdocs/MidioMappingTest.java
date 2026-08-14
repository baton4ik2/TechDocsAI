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
    void workFindsItsDeviceInsideGroupPlanByTitle() {
        var models = java.util.Map.of(
                "372", "ИПР 513-11ИКЗ-А-R3",
                "144", "ИП 101-29-PR-R3",
                "95", "ИП 212-64-R3",
                "211", "ИПДЛ-264.1-100-R3",
                "337", "УДП 513-11ИКЗ-R3");
        var ids = java.util.List.of("372", "144", "95", "211", "337");

        // полное вхождение модели в название — работа идёт своему изделию
        assertThat(MidioMapping.workTargets(
                "Техническое обслуживание извещателя пожарного теплового ИП 101-29-PR-R3", ids, models))
                .containsExactly("144");
        assertThat(MidioMapping.workTargets(
                "Техническое обслуживание извещателя пожарного ручного ИПР 513-11ИКЗ-А-R3", ids, models))
                .containsExactly("372");
        // УДП и ИПР делят числа 513-11 — различаются буквами
        assertThat(MidioMapping.workTargets(
                "Техническое обслуживание \"Устройство дистанционного пуска УДП 513-11 ИКЗ-R3\"", ids, models))
                .containsExactly("337");
    }

    @Test
    void differentModelSpellingStillFindsTheDevice() {
        var models = java.util.Map.of("211", "ИПДЛ-264.1-100-R3", "95", "ИП 212-64-R3");
        // в работе «264/1», в карточке «264.1-100» — совпадение по буквам и первой числовой группе
        assertThat(MidioMapping.workTargets(
                "Технические обслуживание ИПДЛ-264/1-R3", java.util.List.of("211", "95"), models))
                .containsExactly("211");
    }

    @Test
    void genericWorkGoesToTheWholePlan() {
        var models = java.util.Map.of("372", "ИПР 513-11ИКЗ-А-R3", "95", "ИП 212-64-R3");
        var ids = java.util.List.of("372", "95");
        // модель в названии не указана — работа общая для всех изделий плана
        assertThat(MidioMapping.workTargets("Проверка функционирования системы", ids, models))
                .containsExactlyElementsOf(ids);
    }

    @Test
    void zoneWorksAreRecognizedAndRealWordsAreNot() {
        assertThat(MidioMapping.mentionsZone("Техническое обслуживание \"Рубеж Пожарная зона\"")).isTrue();
        assertThat(MidioMapping.mentionsZone("Проверка зоны контроля")).isTrue();
        assertThat(MidioMapping.mentionsZone("Обслуживание зональных оповещателей")).isTrue();
        // «зон» внутри слова — не зона
        assertThat(MidioMapping.mentionsZone("Сезонное обслуживание вентиляции")).isFalse();
        assertThat(MidioMapping.mentionsZone("Чистка озонатора")).isFalse();
        assertThat(MidioMapping.mentionsZone("Проверка горизонтальных участков")).isFalse();
        assertThat(MidioMapping.mentionsZone(null)).isFalse();
    }

    @Test
    void workTypeComesFromTitleNotFromCategory() {
        assertThat(MidioMapping.workType("Внешний осмотр")).isEqualTo("осмотр");
        assertThat(MidioMapping.workType("Техническое обслуживание")).isEqualTo("ТО");
        assertThat(MidioMapping.workType("Продувка оптической системы")).isEqualTo("чистка");
        assertThat(MidioMapping.workType("Нечто своё")).isNull();
    }
}
