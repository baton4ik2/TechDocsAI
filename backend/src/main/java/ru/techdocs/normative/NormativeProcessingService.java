package ru.techdocs.normative;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.techdocs.processing.PageText;
import ru.techdocs.processing.TextExtractor;
import ru.techdocs.storage.FileStorage;

import java.io.InputStream;
import java.util.List;

/**
 * Конвейер обработки сборника СН-2012: загрузка файла → извлечение текста
 * (PDFBox) → парсинг расценок ({@link NormativeRateParser}) → сохранение
 * каталога расценок с полнотекстовым индексом. Тяжёлая AI-обработка не нужна:
 * расценки извлекаются один раз детерминированным парсером, дальше поиск идёт
 * по индексу Postgres — так экономятся токены при генерации смет.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NormativeProcessingService {

    private final NormativeSourcebookRepository sourcebookRepository;
    private final NormativeRateRepository rateRepository;
    private final FileStorage fileStorage;
    private final TextExtractor textExtractor;
    private final NormativeRateParser rateParser;

    @Async("documentProcessingExecutor")
    public void processAsync(Long sourcebookId) {
        process(sourcebookId);
    }

    public void process(Long sourcebookId) {
        NormativeSourcebook sourcebook = sourcebookRepository.findById(sourcebookId).orElse(null);
        if (sourcebook == null) return;

        sourcebook.setStatus(NormativeSourcebook.STATUS_PROCESSING);
        sourcebook.setErrorMessage(null);
        sourcebookRepository.save(sourcebook);

        try {
            // повторная обработка — очищаем ранее извлечённые расценки
            rateRepository.deleteBySourcebookId(sourcebookId);

            TextExtractor.ExtractionResult result;
            try (InputStream input = fileStorage.load(sourcebook.getStoragePath())) {
                result = textExtractor.extract(sourcebook.getOriginalFilename(), null, input);
            }

            if (result.needsOcr()) {
                sourcebook.setStatus(NormativeSourcebook.STATUS_ERROR);
                sourcebook.setErrorMessage("Сборник загружен как скан — распознайте текст (OCR) " +
                        "или загрузите текстовый PDF. Парсинг расценок по изображению не выполняется.");
                sourcebook.setPageCount(result.pages().size());
                sourcebookRepository.save(sourcebook);
                return;
            }

            List<PageText> pages = result.pages();
            List<NormativeRate> rates = rateParser.parse(pages);
            for (NormativeRate rate : rates) {
                rate.setSourcebookId(sourcebookId);
            }
            rateRepository.saveAll(rates);

            sourcebook.setPageCount(pages.size());
            sourcebook.setRateCount(rates.size());
            sourcebook.setStatus(rates.isEmpty()
                    ? NormativeSourcebook.STATUS_ERROR
                    : NormativeSourcebook.STATUS_READY);
            if (rates.isEmpty()) {
                sourcebook.setErrorMessage("Не удалось распознать ни одной расценки. " +
                        "Проверьте, что это текстовый PDF сборника СН-2012 с таблицами расценок.");
            }
            sourcebookRepository.save(sourcebook);
            log.info("Сборник {} обработан: {} страниц, {} расценок",
                    sourcebook.getName(), pages.size(), rates.size());
        } catch (Throwable e) {
            log.error("Ошибка обработки сборника {}: {}", sourcebookId, e.getMessage(), e);
            sourcebook.setStatus(NormativeSourcebook.STATUS_ERROR);
            sourcebook.setErrorMessage("Не удалось обработать сборник. Проверьте, что файл не повреждён. (" +
                    e.getClass().getSimpleName() + ")");
            sourcebookRepository.save(sourcebook);
        }
    }
}
