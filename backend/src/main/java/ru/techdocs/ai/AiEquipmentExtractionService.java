package ru.techdocs.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.techdocs.document.Document;
import ru.techdocs.document.DocumentPage;
import ru.techdocs.document.DocumentPageRepository;
import ru.techdocs.document.DocumentRepository;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;
import ru.techdocs.equipment.EquipmentSource;
import ru.techdocs.equipment.EquipmentSourceRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Извлечение позиций оборудования из обработанных документов (включая OCR-сканы)
 * с помощью языковой модели. Находит страницы, похожие на ведомости и
 * спецификации, извлекает из них позиции и складывает в реестр оборудования
 * со статусом «Требует проверки».
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiEquipmentExtractionService {

    private static final Pattern CANDIDATE_PAGE = Pattern.compile(
            "(ведомост|спецификац|кол-во|кол\\.|количество|перечень оборудован|смонтированн)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final int MAX_PAGES_PER_RUN = 10;
    private static final int MAX_PAGE_CHARS = 6000;

    private final AiClient aiClient;
    private final DocumentRepository documentRepository;
    private final DocumentPageRepository pageRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentSourceRepository equipmentSourceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Состояние извлечения по документам (в памяти — достаточно для MVP). */
    private final java.util.concurrent.ConcurrentHashMap<Long, Progress> progressMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    public record Progress(String status, int totalPages, int processedPages,
                           int created, int skippedDuplicates, String error) {
        static Progress none() { return new Progress("NONE", 0, 0, 0, 0, null); }
    }

    public boolean isAvailable() {
        return aiClient.isConfigured();
    }

    public Progress progressOf(Long documentId) {
        return progressMap.getOrDefault(documentId, Progress.none());
    }

    public boolean isRunning(Long documentId) {
        return "RUNNING".equals(progressOf(documentId).status());
    }

    @Async("documentProcessingExecutor")
    public void extractAsync(Long documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) return;

        List<DocumentPage> candidates = pageRepository.findByDocumentIdOrderByPageNumber(documentId).stream()
                .filter(p -> p.getText() != null && !p.getText().isBlank())
                .filter(p -> CANDIDATE_PAGE.matcher(p.getText()).find())
                .limit(MAX_PAGES_PER_RUN)
                .toList();

        if (candidates.isEmpty()) {
            progressMap.put(documentId, new Progress("DONE", 0, 0, 0, 0,
                    "Страниц с ведомостями или спецификациями в документе не найдено."));
            log.info("Извлечение оборудования из «{}»: страниц с ведомостями/спецификациями не найдено",
                    document.getName());
            return;
        }

        progressMap.put(documentId, new Progress("RUNNING", candidates.size(), 0, 0, 0, null));
        int created = 0;
        int skipped = 0;
        int processed = 0;
        for (DocumentPage page : candidates) {
            try {
                PageResult result = extractFromPage(document, page);
                created += result.created();
                skipped += result.skippedDuplicates();
            } catch (Exception e) {
                log.warn("Извлечение оборудования: страница {} документа «{}» пропущена: {}",
                        page.getPageNumber(), document.getName(), e.getMessage());
            }
            processed++;
            progressMap.put(documentId,
                    new Progress("RUNNING", candidates.size(), processed, created, skipped, null));
        }
        progressMap.put(documentId,
                new Progress("DONE", candidates.size(), processed, created, skipped, null));
        log.info("Извлечение оборудования из «{}» завершено: {} позиций добавлено, {} дубликатов пропущено",
                document.getName(), created, skipped);
    }

    private record PageResult(int created, int skippedDuplicates) {}

    private PageResult extractFromPage(Document document, DocumentPage page) throws Exception {
        String text = page.getText();
        if (text.length() > MAX_PAGE_CHARS) {
            text = text.substring(0, MAX_PAGE_CHARS);
        }

        String systemPrompt = """
                Ты извлекаешь перечень смонтированного оборудования из текста страницы
                технического документа (ведомость, спецификация, акт).
                Верни ТОЛЬКО JSON-массив объектов без пояснений, в формате:
                [{"manufacturer": "...", "name": "...", "model": "...", "quantity": 0, "unit": "шт."}]
                Правила:
                - name — наименование оборудования, model — тип/марка, quantity — число.
                - Если производитель не указан, manufacturer = null.
                - Включай только реальные позиции оборудования с количеством.
                - Не включай материалы (кабель, трубы, короба) и работы.
                - Если на странице нет перечня оборудования — верни [].
                """;
        String answer = aiClient.complete(systemPrompt, "Текст страницы:\n\n" + text);
        if (answer == null) {
            throw new IllegalStateException("ИИ-провайдер не ответил");
        }

        List<Map<String, Object>> items = parseJsonArray(answer);
        int created = 0;
        int skipped = 0;
        for (Map<String, Object> item : items) {
            String name = stringValue(item.get("name"));
            BigDecimal quantity = parseQuantity(item.get("quantity"));
            if (name == null || quantity == null || quantity.signum() <= 0) continue;

            String model = stringValue(item.get("model"));
            if (isDuplicate(document.getFacilityId(), name, model)) {
                skipped++;
                continue;
            }

            Equipment equipment = new Equipment();
            equipment.setFacilityId(document.getFacilityId());
            equipment.setEngineeringSystemId(document.getEngineeringSystemId());
            equipment.setManufacturer(stringValue(item.get("manufacturer")));
            equipment.setName(name);
            equipment.setModel(model);
            equipment.setQuantity(quantity);
            String unit = stringValue(item.get("unit"));
            equipment.setUnit(unit == null ? "шт." : unit);
            equipment.setStatus(Equipment.STATUS_NEEDS_REVIEW);
            equipment.setComment("Извлечено ИИ из «" + document.getOriginalFilename()
                    + "», стр. " + page.getPageNumber() + ". Проверьте данные.");
            equipment = equipmentRepository.save(equipment);

            EquipmentSource source = new EquipmentSource();
            source.setEquipmentId(equipment.getId());
            source.setDocumentId(document.getId());
            source.setPageNumber(page.getPageNumber());
            source.setSourceText(name + (model == null ? "" : " | " + model) + " | " + quantity);
            equipmentSourceRepository.save(source);
            created++;
        }
        return new PageResult(created, skipped);
    }

    /** Модель может обернуть JSON в текст или ```-блок — вырезаем массив по скобкам. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonArray(String answer) throws Exception {
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        return objectMapper.readValue(answer.substring(start, end + 1), List.class);
    }

    private boolean isDuplicate(Long facilityId, String name, String model) {
        String needle = normalize(model != null ? model : name);
        return equipmentRepository.findFiltered(facilityId, null).stream()
                .anyMatch(e -> normalize(e.getModel() != null ? e.getModel() : e.getName()).equals(needle));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).strip();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private BigDecimal parseQuantity(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value).replace(',', '.').replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
