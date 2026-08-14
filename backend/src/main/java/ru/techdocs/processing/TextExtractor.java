package ru.techdocs.processing;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Извлечение текста из файлов разных форматов.
 * PDF — постранично (PDFBox), Excel — по листам (POI),
 * остальные форматы — целиком через Tika с разбиением на псевдостраницы.
 */
@Service
@Slf4j
public class TextExtractor {

    private static final int PSEUDO_PAGE_SIZE = 3500;

    private final Tika tika = new Tika();

    public record ExtractionResult(List<PageText> pages, boolean needsOcr) {}

    public ExtractionResult extract(String filename, String mimeType, InputStream input) throws Exception {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf") || "application/pdf".equals(mimeType)) {
            return extractPdf(input);
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return new ExtractionResult(extractExcel(input), false);
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
            // распознавание изображений — только через OCR (после MVP)
            return new ExtractionResult(List.of(), true);
        }
        String text = tika.parseToString(input);
        return new ExtractionResult(splitIntoPseudoPages(text), false);
    }

    private ExtractionResult extractPdf(InputStream input) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(input))) {
            List<PageText> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            int totalChars = 0;
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf).strip();
                totalChars += text.length();
                pages.add(new PageText(page, text));
            }
            // почти пустой текст при наличии страниц — вероятно, скан
            boolean needsOcr = !pages.isEmpty() && totalChars < 20L * pages.size();
            return new ExtractionResult(pages, needsOcr);
        }
    }

    private List<PageText> extractExcel(InputStream input) throws Exception {
        List<PageText> pages = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                StringBuilder sb = new StringBuilder("Лист: ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell).strip();
                        cells.add(value);
                    }
                    String line = String.join(" | ", cells).strip();
                    if (!line.isBlank() && !line.replace("|", "").isBlank()) {
                        sb.append(line).append('\n');
                    }
                }
                pages.add(new PageText(i + 1, sb.toString().strip()));
            }
        }
        return pages;
    }

    private List<PageText> splitIntoPseudoPages(String text) {
        List<PageText> pages = new ArrayList<>();
        String stripped = text == null ? "" : text.strip();
        if (stripped.isEmpty()) {
            return pages;
        }
        int pageNumber = 1;
        for (int offset = 0; offset < stripped.length(); offset += PSEUDO_PAGE_SIZE) {
            int end = Math.min(offset + PSEUDO_PAGE_SIZE, stripped.length());
            pages.add(new PageText(pageNumber++, stripped.substring(offset, end)));
        }
        return pages;
    }
}
