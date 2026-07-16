package ru.techdocs.processing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Извлечение текста из сканов: PDF рендерится в изображения постранично,
 * JPG/PNG распознаются напрямую.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcrExtractor {

    private final OcrService ocrService;

    @Value("${techdocs.ocr.dpi:200}")
    private int dpi;

    @Value("${techdocs.ocr.max-pages:100}")
    private int maxPages;

    public boolean isAvailable() {
        return ocrService.isAvailable();
    }

    public List<PageText> extract(String filename, InputStream input) throws Exception {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return extractPdf(input);
        }
        // JPG / PNG
        BufferedImage image = ImageIO.read(input);
        if (image == null) {
            throw new IllegalStateException("Не удалось прочитать изображение");
        }
        String text = ocrService.recognize(image);
        List<PageText> pages = new ArrayList<>();
        pages.add(new PageText(1, text));
        return pages;
    }

    private List<PageText> extractPdf(InputStream input) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(input))) {
            int pageCount = Math.min(pdf.getNumberOfPages(), maxPages);
            PDFRenderer renderer = new PDFRenderer(pdf);
            List<PageText> pages = new ArrayList<>();
            for (int i = 0; i < pageCount; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.GRAY);
                String text = ocrService.recognize(image);
                pages.add(new PageText(i + 1, text));
                log.debug("OCR: страница {}/{} распознана, {} символов", i + 1, pageCount, text.length());
            }
            return pages;
        }
    }
}
