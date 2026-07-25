package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.common.SystemNormalizer;

import static org.assertj.core.api.Assertions.assertThat;

class SystemNormalizerTest {

    @Test
    void sectionHeaderAndShortNameMapToSameToken() {
        // развёрнутый заголовок раздела эталона и короткое имя системы объекта — один токен
        assertThat(SystemNormalizer.canonical("РАЗДЕЛ А. СИСТЕМА КОНТРОЛЯ И УПРАВЛЕНИЯ ДОСТУПОМ"))
                .isEqualTo(SystemNormalizer.canonical("СКУД"))
                .isEqualTo("скуд");
        assertThat(SystemNormalizer.canonical("РАЗДЕЛ Б. ДОМОФОНИЯ"))
                .isEqualTo(SystemNormalizer.canonical("Домофония"))
                .isEqualTo("домофония");
    }

    @Test
    void firePpsSynonymsMerge() {
        assertThat(SystemNormalizer.canonical("Пожарная сигнализация"))
                .isEqualTo(SystemNormalizer.canonical("СПС"))
                .isEqualTo("апс");
    }

    @Test
    void recognizedNullForServiceRows() {
        // «ИТОГО» и пустые строки не должны становиться системой
        assertThat(SystemNormalizer.recognized("ИТОГО")).isNull();
        assertThat(SystemNormalizer.recognized("")).isNull();
        assertThat(SystemNormalizer.recognized(null)).isNull();
        assertThat(SystemNormalizer.recognized("СКУД")).isEqualTo("скуд");
    }

    @Test
    void canonicalFallsBackToCompactForUnknown() {
        assertThat(SystemNormalizer.canonical("Слаботочка XZ")).isEqualTo("слаботочкаxz");
        assertThat(SystemNormalizer.canonical(null)).isNull();
    }
}
