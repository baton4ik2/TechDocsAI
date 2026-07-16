package ru.techdocs.processing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.techdocs.document.*;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;
import ru.techdocs.equipment.EquipmentSource;
import ru.techdocs.equipment.EquipmentSourceRepository;
import ru.techdocs.equipment.XlsxEquipmentExtractor;
import ru.techdocs.storage.FileStorage;

import java.io.InputStream;
import java.util.List;

/**
 * Конвейер обработки документа: извлечение текста → страницы → фрагменты →
 * (для Excel-спецификаций) извлечение оборудования → статус "Готов".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentPageRepository pageRepository;
    private final DocumentChunkRepository chunkRepository;
    private final FileStorage fileStorage;
    private final TextExtractor textExtractor;
    private final TextChunker textChunker;
    private final XlsxEquipmentExtractor equipmentExtractor;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentSourceRepository equipmentSourceRepository;

    @Async("documentProcessingExecutor")
    public void processAsync(Long documentId) {
        process(documentId);
    }

    public void process(Long documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) return;

        document.setStatus(Document.STATUS_PROCESSING);
        document.setErrorMessage(null);
        documentRepository.save(document);

        try {
            // старые результаты обработки удаляем (повторная обработка)
            pageRepository.deleteByDocumentId(documentId);
            chunkRepository.deleteByDocumentId(documentId);

            TextExtractor.ExtractionResult result;
            try (InputStream input = fileStorage.load(document.getStoragePath())) {
                result = textExtractor.extract(document.getOriginalFilename(), document.getMimeType(), input);
            }

            if (result.needsOcr()) {
                document.setStatus(Document.STATUS_NEEDS_OCR);
                document.setErrorMessage("Не удалось извлечь текст. Возможно, документ является сканом — требуется OCR.");
                document.setPageCount(result.pages().size());
                documentRepository.save(document);
                return;
            }

            List<PageText> pages = result.pages();
            for (PageText pageText : pages) {
                DocumentPage page = new DocumentPage();
                page.setDocumentId(documentId);
                page.setPageNumber(pageText.pageNumber());
                page.setText(pageText.text());
                pageRepository.save(page);
            }

            chunkRepository.saveAll(textChunker.chunk(documentId, pages));

            if (isExcel(document.getOriginalFilename())) {
                extractEquipment(document);
            }

            document.setPageCount(pages.size());
            document.setStatus(Document.STATUS_READY);
            documentRepository.save(document);
            log.info("Документ {} обработан: {} страниц", document.getName(), pages.size());
        } catch (Throwable e) {
            // ловим и Error тоже (например, NoSuchMethodError из-за конфликта библиотек),
            // чтобы документ не завис в статусе "Обрабатывается"
            log.error("Ошибка обработки документа {}: {}", documentId, e.getMessage(), e);
            document.setStatus(Document.STATUS_ERROR);
            document.setErrorMessage(friendlyMessage(e));
            documentRepository.save(document);
        }
    }

    private void extractEquipment(Document document) {
        try (InputStream input = fileStorage.load(document.getStoragePath())) {
            List<XlsxEquipmentExtractor.ExtractedItem> items = equipmentExtractor.extract(document, input);
            for (var item : items) {
                Equipment equipment = new Equipment();
                equipment.setFacilityId(document.getFacilityId());
                equipment.setEngineeringSystemId(document.getEngineeringSystemId());
                equipment.setManufacturer(emptyToNull(item.manufacturer()));
                equipment.setName(item.name());
                equipment.setModel(emptyToNull(item.model()));
                equipment.setQuantity(item.quantity());
                equipment.setUnit(item.unit());
                equipment.setStatus(Equipment.STATUS_AUTO);
                equipment = equipmentRepository.save(equipment);

                EquipmentSource source = new EquipmentSource();
                source.setEquipmentId(equipment.getId());
                source.setDocumentId(document.getId());
                source.setPageNumber(item.sheetNumber());
                source.setSourceText(item.sourceRow());
                equipmentSourceRepository.save(source);
            }
            if (!items.isEmpty()) {
                log.info("Из документа {} извлечено {} позиций оборудования",
                        document.getName(), items.size());
            }
        } catch (Exception e) {
            log.warn("Извлечение оборудования из {} не удалось: {}",
                    document.getName(), e.getMessage());
        }
    }

    private boolean isExcel(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String friendlyMessage(Throwable e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("password") || message.contains("encrypted")) {
            return "Документ защищён паролем. Снимите защиту и загрузите файл повторно.";
        }
        return "Не удалось обработать документ. Проверьте, что файл не повреждён. (" +
                e.getClass().getSimpleName() + ")";
    }
}
