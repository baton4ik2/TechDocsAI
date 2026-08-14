package ru.techdocs.processing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Распознавание текста на изображениях через Tesseract CLI.
 * Вызов бинарника надёжнее JNA-обёрток (tess4j): нет конфликтов нативных библиотек.
 * Если tesseract не установлен, OCR считается недоступным и документ
 * остаётся в статусе "Требуется OCR".
 */
@Service
@Slf4j
public class OcrService {

    private final String tesseractCmd;
    private final String languages;
    private final int timeoutSeconds;
    private volatile Boolean available;

    public OcrService(@Value("${techdocs.ocr.command:tesseract}") String tesseractCmd,
                      @Value("${techdocs.ocr.languages:rus+eng}") String languages,
                      @Value("${techdocs.ocr.page-timeout-seconds:120}") int timeoutSeconds) {
        this.tesseractCmd = tesseractCmd;
        this.languages = languages;
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (this) {
            if (available != null) return available;
            try {
                Process process = new ProcessBuilder(tesseractCmd, "--version")
                        .redirectErrorStream(true).start();
                boolean finished = process.waitFor(10, TimeUnit.SECONDS);
                available = finished && process.exitValue() == 0;
            } catch (Exception e) {
                available = false;
            }
            if (!available) {
                log.warn("Tesseract не найден — OCR отключён. Установите tesseract-ocr и tesseract-ocr-rus.");
            } else {
                log.info("OCR доступен: tesseract, языки: {}", languages);
            }
            return available;
        }
    }

    /** Распознаёт текст на изображении. Возвращает пустую строку, если ничего не найдено. */
    public String recognize(BufferedImage image) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("ocr");
        Path inputPng = tempDir.resolve("page.png");
        Path outputBase = tempDir.resolve("out");
        try {
            ImageIO.write(image, "png", inputPng.toFile());
            Process process = new ProcessBuilder(
                    tesseractCmd, inputPng.toString(), outputBase.toString(), "-l", languages)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("OCR превысил лимит времени (" + timeoutSeconds + " с) на страницу");
            }
            Path txt = tempDir.resolve("out.txt");
            if (process.exitValue() != 0 || !Files.exists(txt)) {
                throw new IOException("Tesseract завершился с ошибкой (код " + process.exitValue() + ")");
            }
            return Files.readString(txt).strip();
        } finally {
            try (var files = Files.walk(tempDir)) {
                files.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
            }
        }
    }
}
