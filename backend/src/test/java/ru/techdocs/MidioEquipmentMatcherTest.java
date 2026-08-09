package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.midio.MidioEquipmentMatcher;
import ru.techdocs.midio.MidioEquipmentMatcher.ExternalEquipment;
import ru.techdocs.midio.MidioEquipmentMatcher.Kind;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentRepository;
import ru.techdocs.uniqueequipment.UniqueEquipmentService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Узнавание оборудования Midio в нашем реестре. Ошибка здесь стоит дорого:
 * привязали не к тому изделию — и в смету поедет чужая периодичность.
 */
class MidioEquipmentMatcherTest {

    private final MidioEquipmentMatcher matcher =
            new MidioEquipmentMatcher(mock(UniqueEquipmentRepository.class));

    private UniqueEquipment ue(Long id, String name, String model, String manufacturer, String system) {
        UniqueEquipment u = new UniqueEquipment();
        u.setId(id);
        u.setName(name);
        u.setModel(model);
        u.setManufacturer(manufacturer);
        u.setNormKey(UniqueEquipmentService.normKey(name, model, manufacturer, system));
        u.setEquipKey(UniqueEquipmentService.equipKey(name, model, manufacturer));
        return u;
    }

    @Test
    void alreadyLinkedWinsOverEverythingElse() {
        UniqueEquipment linked = ue(1L, "Совсем другое название", "XYZ-1", "Другой", "АПС");
        linked.setMidioId("mid-77");
        UniqueEquipment sameName = ue(2L, "Модуль релейный", "РМ-4-R3", "Рубеж", "АПС");

        var m = matcher.match(new ExternalEquipment("mid-77", "Модуль релейный", "РМ-4-R3", "Рубеж", "АПС"),
                List.of(linked, sameName));

        // связь по идентификатору сильнее совпадения по тексту: на той стороне могли переименовать
        assertThat(m.kind()).isEqualTo(Kind.LINKED);
        assertThat(m.target().getId()).isEqualTo(1L);
    }

    @Test
    void exactKeyMatches() {
        UniqueEquipment target = ue(1L, "Модуль релейный", "РМ-4-R3", "Рубеж", "АПС");

        var m = matcher.match(new ExternalEquipment("mid-1", "Модуль релейный", "РМ-4-R3", "Рубеж", "АПС"),
                List.of(target));

        assertThat(m.kind()).isEqualTo(Kind.EXACT);
        assertThat(m.target().getId()).isEqualTo(1L);
    }

    @Test
    void systemMismatchStillMatchesByEquipmentKey() {
        UniqueEquipment target = ue(1L, "Модуль релейный", "РМ-4-R3", "Рубеж", "АПС");

        // раздел на стороне Midio назван иначе — терять из-за этого связь нельзя
        var m = matcher.match(new ExternalEquipment("mid-1", "Модуль релейный", "РМ-4-R3", "Рубеж",
                "Пожарная сигнализация"), List.of(target));

        assertThat(m.kind()).isEqualTo(Kind.EXACT);
        assertThat(m.target().getId()).isEqualTo(1L);
    }

    @Test
    void sameModelInTwoSystemsIsAmbiguous() {
        UniqueEquipment aps = ue(1L, "Модуль релейный", "РМ-4-R3", "Рубеж", "АПС");
        UniqueEquipment soue = ue(2L, "Модуль релейный", "РМ-4-R3", "Рубеж", "СОУЭ");

        var m = matcher.match(new ExternalEquipment("mid-1", "Модуль релейный", "РМ-4-R3", "Рубеж", null),
                List.of(aps, soue));

        assertThat(m.kind()).isEqualTo(Kind.AMBIGUOUS);
        assertThat(m.target()).isNull();
        assertThat(m.candidates()).hasSize(2);
    }

    @Test
    void protocolMarkerDoesNotBreakTheMatch() {
        UniqueEquipment target = ue(1L, "Модуль релейный", "РМ-4 прот. R3", "Рубеж", "АПС");

        var m = matcher.match(new ExternalEquipment("mid-1", "Релейный модуль", "РМ-4-R3", "Рубеж", "АПС"),
                List.of(target));

        assertThat(m.kind()).isEqualTo(Kind.SIMILAR);
        assertThat(m.target().getId()).isEqualTo(1L);
    }

    @Test
    void differentNumberInModelIsNotTheSameDevice() {
        UniqueEquipment rm1 = ue(1L, "Модуль релейный", "РМ-1К", "Рубеж", "АПС");
        UniqueEquipment rm4 = ue(2L, "Модуль релейный", "РМ-4", "Рубеж", "АПС");

        // «РМ-4К» одинаково близок к обоим — разводим по числу в модели
        var m = matcher.match(new ExternalEquipment("mid-1", "Модуль релейный", "РМ-4К", "Рубеж", "АПС"),
                List.of(rm1, rm4));

        assertThat(m.kind()).isEqualTo(Kind.SIMILAR);
        assertThat(m.target().getId()).isEqualTo(2L);
    }

    @Test
    void unknownEquipmentIsLeftUnmatched() {
        UniqueEquipment target = ue(1L, "Модуль релейный", "РМ-4-R3", "Рубеж", "АПС");

        var m = matcher.match(new ExternalEquipment("mid-9", "Насос дренажный", "GRUNDFOS UNILIFT",
                "Grundfos", "Водоснабжение"), List.of(target));

        assertThat(m.kind()).isEqualTo(Kind.NONE);
        assertThat(m.target()).isNull();
    }

    @Test
    void tooShortModelIsNotGuessed() {
        UniqueEquipment target = ue(1L, "Извещатель", "ИП 212-64", "Рубеж", "АПС");

        var m = matcher.match(new ExternalEquipment("mid-9", "Извещатель", "ИП", "Рубеж", "АПС"),
                List.of(target));

        assertThat(m.kind()).isEqualTo(Kind.NONE);
    }
}
