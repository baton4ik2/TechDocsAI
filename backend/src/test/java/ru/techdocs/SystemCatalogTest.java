package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.common.SystemCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Справочник инженерных систем — константа приложения. Любое входящее написание
 * приводится к одному стандартному названию, иначе реестр оборудования двоится
 * («апс» и «Пожарная сигнализация» как две разные системы).
 */
class SystemCatalogTest {

    @Test
    void allSpellingsCollapseToOneStandardName() {
        assertThat(SystemCatalog.standardName("апс")).isEqualTo("Пожарная сигнализация");
        assertThat(SystemCatalog.standardName("АПС")).isEqualTo("Пожарная сигнализация");
        assertThat(SystemCatalog.standardName("Пожарная сигнализация")).isEqualTo("Пожарная сигнализация");
        assertThat(SystemCatalog.standardName("Система автоматической пожарной сигнализации"))
                .isEqualTo("Пожарная сигнализация");
        assertThat(SystemCatalog.standardName("соуэ")).isEqualTo("СОУЭ");
        assertThat(SystemCatalog.standardName("СОУЭ")).isEqualTo("СОУЭ");
        assertThat(SystemCatalog.standardName("Система оповещения и управления эвакуацией"))
                .isEqualTo("СОУЭ");
    }

    @Test
    void newSystemsFromTheRealFleetAreRecognized() {
        assertThat(SystemCatalog.standardName("АДУ")).isEqualTo("Дымоудаление (АДУ)");
        assertThat(SystemCatalog.standardName("Дымоудаление")).isEqualTo("Дымоудаление (АДУ)");
        assertThat(SystemCatalog.standardName("АОВ")).isEqualTo("АОВ");
        assertThat(SystemCatalog.standardName("Кондиционирование")).isEqualTo("Кондиционирование");
        assertThat(SystemCatalog.standardName("ПНС")).isEqualTo("ПНС");
        assertThat(SystemCatalog.standardName("КНС")).isEqualTo("КНС");
        assertThat(SystemCatalog.standardName("Энергоучет")).isEqualTo("Энергоучёт");
    }

    @Test
    void unknownNameIsNotGuessed() {
        assertThat(SystemCatalog.standardName("Фонтаны")).isNull();
        assertThat(SystemCatalog.standardName("")).isNull();
        assertThat(SystemCatalog.standardName(null)).isNull();
    }

    @Test
    void catalogListIsNotEmptyAndHasNoDuplicates() {
        var names = SystemCatalog.names();
        assertThat(names).isNotEmpty();
        assertThat(names).doesNotHaveDuplicates();
        // каждое стандартное название узнаёт само себя — иначе выбор из списка отвергался бы
        for (String name : names) {
            assertThat(SystemCatalog.standardName(name)).as(name).isEqualTo(name);
        }
    }
}
