package ru.techdocs;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ru.techdocs.processing.PageText;
import ru.techdocs.processing.TextExtractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    void extractsPlainText() throws Exception {
        byte[] txt = "Назначение прибора С2000-ПП — передача извещений.".getBytes(StandardCharsets.UTF_8);
        TextExtractor.ExtractionResult result = extractor.extract("passport.txt", "text/plain",
                new ByteArrayInputStream(txt));

        assertThat(result.needsOcr()).isFalse();
        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().getFirst().text()).contains("С2000-ПП");
    }

    @Test
    void extractsExcelSheetsAsPages() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s1 = wb.createSheet("Лист1");
            Row r = s1.createRow(0);
            r.createCell(0).setCellValue("Оборудование");
            r.createCell(1).setCellValue("Кол-во");
            wb.createSheet("Лист2");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            xlsx = out.toByteArray();
        }
        TextExtractor.ExtractionResult result = extractor.extract("spec.xlsx", null,
                new ByteArrayInputStream(xlsx));

        assertThat(result.needsOcr()).isFalse();
        assertThat(result.pages()).hasSize(2); // два листа = две страницы
        assertThat(result.pages().getFirst().text()).contains("Оборудование");
    }

    @Test
    void imagesRequireOcr() throws Exception {
        TextExtractor.ExtractionResult result = extractor.extract("scan.jpg", "image/jpeg",
                new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThat(result.needsOcr()).isTrue();
        assertThat(result.pages()).isEmpty();
    }

    @Test
    void largeTextSplitsIntoPseudoPages() throws Exception {
        byte[] txt = "А".repeat(9000).getBytes(StandardCharsets.UTF_8);
        TextExtractor.ExtractionResult result = extractor.extract("big.txt", "text/plain",
                new ByteArrayInputStream(txt));
        assertThat(result.pages().size()).isGreaterThan(1);
        assertThat(result.pages()).extracting(PageText::pageNumber).doesNotHaveDuplicates();
    }
}
