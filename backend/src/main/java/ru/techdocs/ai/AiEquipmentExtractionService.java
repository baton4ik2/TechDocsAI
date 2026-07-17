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

    private static final int MAX_PAGES_PER_RUN = 10;
    private static final int MAX_PAGE_CHARS = 6000;

    @org.springframework.beans.factory.annotation.Value("${techdocs.ai.vision-dpi:220}")
    private int visionDpi;

    private final AiClient aiClient;
    private final DocumentRepository documentRepository;
    private final DocumentPageRepository pageRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentSourceRepository equipmentSourceRepository;
    private final ru.techdocs.processing.PageImageRenderer pageImageRenderer;
    private final ru.techdocs.storage.FileStorage fileStorage;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Состояние извлечения по документам (в памяти — достаточно для MVP). */
    private final java.util.concurrent.ConcurrentHashMap<Long, Progress> progressMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    public record Progress(String status, int totalPages, int processedPages,
                           int created, int skippedDuplicates, int failedPages, String error) {
        static Progress none() { return new Progress("NONE", 0, 0, 0, 0, 0, null); }
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
    public void extractAsync(Long documentId, java.util.Set<Integer> requestedPages) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) return;

        List<DocumentPage> allPages = pageRepository.findByDocumentIdOrderByPageNumber(documentId);

        List<DocumentPage> candidates;
        if (requestedPages != null && !requestedPages.isEmpty()) {
            // Пользователь указал точные страницы — обрабатываем только их, это быстро.
            // С vision-моделью страница годится даже без распознанного текста.
            boolean vision = aiClient.hasVisionModel();
            candidates = allPages.stream()
                    .filter(p -> requestedPages.contains(p.getPageNumber()))
                    .filter(p -> vision || (p.getText() != null && !p.getText().isBlank()))
                    .limit(MAX_PAGES_PER_RUN)
                    .toList();
        } else {
            // авто-режим: ранжируем страницы по признакам ведомости/спецификации
            candidates = allPages.stream()
                    .filter(p -> p.getText() != null && !p.getText().isBlank())
                    .map(p -> Map.entry(p, candidateScore(p.getText())))
                    .filter(e -> e.getValue() > 0)
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(MAX_PAGES_PER_RUN)
                    .map(Map.Entry::getKey)
                    .sorted(java.util.Comparator.comparingInt(DocumentPage::getPageNumber))
                    .toList();
        }

        if (candidates.isEmpty()) {
            String reason = (requestedPages != null && !requestedPages.isEmpty())
                    ? "Указанные страницы не найдены в документе или на них нет текста."
                    : "Страниц с ведомостями или спецификациями в документе не найдено.";
            progressMap.put(documentId, new Progress("DONE", 0, 0, 0, 0, 0, reason));
            log.info("Извлечение оборудования из «{}»: {}", document.getName(), reason);
            return;
        }

        progressMap.put(documentId, new Progress("RUNNING", candidates.size(), 0, 0, 0, 0, null));
        int created = 0;
        int skipped = 0;
        int processed = 0;
        int failed = 0;
        String lastError = null;
        for (DocumentPage page : candidates) {
            try {
                PageResult result = extractFromPage(document, page);
                created += result.created();
                skipped += result.skippedDuplicates();
            } catch (Exception e) {
                failed++;
                lastError = e.getMessage();
                log.warn("Извлечение оборудования: страница {} документа «{}» пропущена: {}",
                        page.getPageNumber(), document.getName(), e.getMessage());
            }
            processed++;
            progressMap.put(documentId,
                    new Progress("RUNNING", candidates.size(), processed, created, skipped, failed, null));
        }

        // если позиций нет — объясняем причину, а не молчим
        String summary = null;
        if (created == 0 && skipped == 0) {
            if (failed == processed) {
                summary = "Ни одна страница не обработана (" + lastError + "). " +
                        "Проверьте, что ИИ-провайдер работает, и попробуйте ещё раз.";
            } else {
                summary = "Модель не нашла перечня оборудования на обработанных страницах. " +
                        "Проверьте номера страниц и качество распознавания текста.";
            }
        } else if (failed > 0) {
            summary = failed + " из " + processed + " страниц не обработаны — можно повторить запуск.";
        }
        progressMap.put(documentId,
                new Progress("DONE", candidates.size(), processed, created, skipped, failed, summary));
        log.info("Извлечение оборудования из «{}» завершено: {} добавлено, {} дубликатов, {} страниц с ошибками",
                document.getName(), created, skipped, failed);
    }

    private record PageResult(int created, int skippedDuplicates) {}

    /**
     * Оценка «похожести» страницы на ведомость/спецификацию оборудования.
     * Сильные признаки — заголовки таблиц, слабые — упоминания единиц измерения.
     */
    private int candidateScore(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;
        if (lower.contains("ведомост")) score += 5;
        if (lower.contains("смонтированн")) score += 4;
        if (lower.contains("спецификац")) score += 3;
        if (lower.contains("перечень оборудован")) score += 3;
        if (lower.contains("кол-во") || lower.contains("количество") || lower.contains("кол.")) score += 2;
        // плотность единиц измерения: у таблиц с оборудованием "шт" встречается много раз
        int idx = 0;
        int count = 0;
        while ((idx = lower.indexOf("шт", idx)) >= 0 && count < 10) {
            count++;
            idx += 2;
        }
        score += Math.min(count, 10);
        // страницы без цифр бесполезны для количеств
        if (lower.chars().noneMatch(Character::isDigit)) score = 0;
        return score;
    }

    private PageResult extractFromPage(Document document, DocumentPage page) throws Exception {
        String answer = null;

        // Vision-путь: модель читает картинку страницы напрямую, без OCR —
        // на сканах это радикально точнее. При сбое откатываемся на текст.
        if (aiClient.hasVisionModel() && isRenderable(document.getOriginalFilename())) {
            try {
                byte[] png;
                try (java.io.InputStream input = fileStorage.load(document.getStoragePath())) {
                    png = pageImageRenderer.renderPng(
                            document.getOriginalFilename(), input, page.getPageNumber(), visionDpi);
                }
                answer = aiClient.completeVision(visionSystemPrompt(),
                        "Извлеки перечень оборудования со страницы на изображении.", png);
                if (answer != null) {
                    log.info("Извлечение (vision): «{}», стр. {}", document.getName(), page.getPageNumber());
                }
            } catch (Exception e) {
                log.warn("Vision-извлечение не удалось (стр. {}): {} — пробую по тексту",
                        page.getPageNumber(), e.getMessage());
            }
        }

        if (answer == null) {
            answer = extractByText(page);
        }
        if (answer == null) {
            throw new IllegalStateException("ИИ-провайдер не ответил или превышено время ожидания");
        }
        if (!answer.contains("[")) {
            log.debug("Ответ модели без JSON: {}", answer);
            throw new IllegalStateException("модель вернула ответ не в формате JSON");
        }

        List<Map<String, Object>> items = parseJsonArray(answer);
        if (items.isEmpty()) {
            // пустой список — не ошибка, но для диагностики важно видеть, что именно ответила модель
            log.info("Извлечение: модель не нашла позиций на стр. {} («{}»). Ответ модели: {}",
                    page.getPageNumber(), document.getName(),
                    answer.length() > 400 ? answer.substring(0, 400) + "…" : answer);
        }
        return saveItems(document, page, items);
    }

    private boolean isRenderable(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private String visionSystemPrompt() {
        return """
                Ты извлекаешь перечень смонтированного оборудования со страницы
                технического документа (ведомость, спецификация, акт) по её изображению.
                Верни ТОЛЬКО JSON-массив объектов без пояснений, в формате:
                [{"manufacturer": "...", "name": "...", "model": "...", "quantity": 0, "unit": "шт."}]
                Правила:
                - name — наименование оборудования, model — тип/марка, quantity — число.
                - Извлекай КАЖДУЮ строку таблицы отдельной позицией, ничего не объединяй.
                - Если производитель не указан, manufacturer = null.
                - Не включай материалы (кабель, трубы, короба) и работы.
                - Если перечня оборудования на странице нет — верни [].
                """;
    }

    private String extractByText(DocumentPage page) {
        String text = page.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
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
                - Извлекай КАЖДУЮ строку таблицы отдельной позицией. Разные позиции могут
                  иметь одинаковый тип (например, ОПОП 1-8 с надписями «ВЫХОД»,
                  «Стрелка влево», звуковой) — обязательно сохраняй отличительные
                  признаки в name и не объединяй такие строки.
                - Если производитель не указан, manufacturer = null.
                - Включай только реальные позиции оборудования с количеством.
                - Не включай материалы (кабель, трубы, короба) и работы.
                - Текст получен OCR-распознаванием скана. Исправляй очевидные ошибки:
                  перепутанные латинские и кириллические буквы, лишние символы | _ из таблиц.
                  Пиши термины правильно: "Оповещатель", "Громкоговоритель", "исп." и т.д.
                - Если строка распознана нечитаемо и наименование восстановить нельзя — пропусти её.
                - Если на странице нет перечня оборудования — верни [].
                """;
        return aiClient.complete(systemPrompt, "Текст страницы:\n\n" + text);
    }

    private PageResult saveItems(Document document, DocumentPage page, List<Map<String, Object>> items) {
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

    /**
     * Дубликат — совпадение и наименования, и модели. Сравнивать только по модели
     * нельзя: в ведомостях бывают разные позиции с одним типом (например, ОПОП 1-8
     * с надписями «ВЫХОД», «Стрелка влево», звуковой) — это разные строки.
     */
    private boolean isDuplicate(Long facilityId, String name, String model) {
        String needle = normalize(name) + "|" + normalize(model);
        return equipmentRepository.findFiltered(facilityId, null).stream()
                .anyMatch(e -> (normalize(e.getName()) + "|" + normalize(e.getModel())).equals(needle));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String s = cleanOcrArtifacts(String.valueOf(value));
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    // Латиница ↔ кириллица: пары букв, неотличимые визуально (гомоглифы).
    private static final String LATIN_LOOKALIKES = "ABCEHKMOPTXYaceopxy";
    private static final String CYRILLIC_LOOKALIKES = "АВСЕНКМОРТХУасеорху";

    /**
     * Чистка артефактов OCR в извлечённых полях: обрезка символов таблиц (| _ и т.п.)
     * и починка слов со смешанными алфавитами — «LPA-6С» (кириллическая С) → «LPA-6C»,
     * «ОПОП» с латинскими O/П-подменами → кириллица.
     */
    private String cleanOcrArtifacts(String value) {
        if (value == null) return null;
        String s = value.strip()
                .replaceAll("^[|_\\-–—•.,;:\\s]+", "")
                .replaceAll("[|_•\\s]+$", "")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s*\\|\\s*", " ");
        StringBuilder result = new StringBuilder();
        for (String token : s.split(" ")) {
            if (!result.isEmpty()) result.append(' ');
            result.append(normalizeToken(token));
        }
        return result.toString().strip();
    }

    /** Внутри одного слова приводим буквы-двойники к преобладающему алфавиту. */
    private String normalizeToken(String token) {
        long cyrillic = token.chars().filter(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC).count();
        long latin = token.chars().filter(c -> c < 128 && Character.isLetter(c)).count();
        if (cyrillic == 0 || latin == 0) return token; // алфавиты не смешаны
        boolean toCyrillic = cyrillic >= latin;
        String from = toCyrillic ? LATIN_LOOKALIKES : CYRILLIC_LOOKALIKES;
        String to = toCyrillic ? CYRILLIC_LOOKALIKES : LATIN_LOOKALIKES;
        StringBuilder sb = new StringBuilder(token.length());
        for (char c : token.toCharArray()) {
            int idx = from.indexOf(c);
            sb.append(idx >= 0 ? to.charAt(idx) : c);
        }
        return sb.toString();
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
