package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.ai.OcrTextCleaner;

import static org.assertj.core.api.Assertions.assertThat;

class OcrTextCleanerTest {

    @Test
    void trimsTableArtifacts() {
        assertThat(OcrTextCleaner.clean("| LPA-10W")).isEqualTo("LPA-10W");
        assertThat(OcrTextCleaner.clean("_РБЬ")).isEqualTo("РБЬ");
        assertThat(OcrTextCleaner.clean("  Громкоговоритель  |  LPA-6W  "))
                .isEqualTo("Громкоговоритель LPA-6W");
    }

    @Test
    void fixesCyrillicInsideLatinModel() {
        // кириллическая С в латинской модели → латинская C
        assertThat(OcrTextCleaner.clean("LPA-6С 0.75")).isEqualTo("LPA-6C 0.75");
    }

    @Test
    void keepsPureCyrillicAndPureLatin() {
        assertThat(OcrTextCleaner.clean("Оповещатель")).isEqualTo("Оповещатель");
        assertThat(OcrTextCleaner.clean("LPA-DUO-MIC")).isEqualTo("LPA-DUO-MIC");
        assertThat(OcrTextCleaner.clean("12 В 12 Ач НК")).isEqualTo("12 В 12 Ач НК");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(OcrTextCleaner.clean(null)).isNull();
        assertThat(OcrTextCleaner.clean("   ")).isEmpty();
    }

    @Test
    void picksDominantAlphabetInMixedToken() {
        // больше кириллицы → латинские двойники (O) становятся кириллицей
        assertThat(OcrTextCleaner.clean("ОПOП 1-8")).isEqualTo("ОПОП 1-8");
    }
}
