package ru.techdocs.pkm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.techdocs.storage.FileStorage;

import java.io.InputStream;
import java.util.List;

/** Конвейер обработки регламента ПКМ: файл → парсинг таблицы → операции. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PkmProcessingService {

    private final PkmDocumentRepository documentRepository;
    private final PkmOperationRepository operationRepository;
    private final FileStorage fileStorage;
    private final PkmParser parser;

    @Async("documentProcessingExecutor")
    public void processAsync(Long pkmId) {
        process(pkmId);
    }

    public void process(Long pkmId) {
        PkmDocument document = documentRepository.findById(pkmId).orElse(null);
        if (document == null) return;

        document.setStatus(PkmDocument.STATUS_PROCESSING);
        document.setErrorMessage(null);
        documentRepository.save(document);

        try {
            operationRepository.deleteByPkmId(pkmId);

            PkmParser.ParseResult result;
            try (InputStream input = fileStorage.load(document.getStoragePath())) {
                result = parser.parse(document.getOriginalFilename(), input);
            }
            // тип системы не задан вручную — берём из самого файла (JSON)
            if ((document.getSystemType() == null || document.getSystemType().isBlank())
                    && result.systemType() != null) {
                document.setSystemType(result.systemType());
            }
            List<PkmOperation> operations = result.operations();
            for (PkmOperation op : operations) {
                op.setPkmId(pkmId);
                op.setSystemType(document.getSystemType());
            }
            operationRepository.saveAll(operations);

            document.setOperationCount(operations.size());
            document.setStatus(operations.isEmpty()
                    ? PkmDocument.STATUS_ERROR
                    : PkmDocument.STATUS_READY);
            if (operations.isEmpty()) {
                document.setErrorMessage("Не удалось найти таблицу работ с колонкой «Периодичность». " +
                        "Проверьте, что это регламент ПКМ в формате DOCX.");
            }
            documentRepository.save(document);
            log.info("Регламент ПКМ {} обработан: {} операций", document.getName(), operations.size());
        } catch (Throwable e) {
            log.error("Ошибка обработки регламента ПКМ {}: {}", pkmId, e.getMessage(), e);
            document.setStatus(PkmDocument.STATUS_ERROR);
            document.setErrorMessage("Не удалось обработать файл. Проверьте, что это корректный DOCX. (" +
                    e.getClass().getSimpleName() + ")");
            documentRepository.save(document);
        }
    }
}
