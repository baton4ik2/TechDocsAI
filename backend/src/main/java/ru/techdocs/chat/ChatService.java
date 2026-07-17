package ru.techdocs.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.ai.AiClient;
import ru.techdocs.document.Document;
import ru.techdocs.document.DocumentChunkRepository;
import ru.techdocs.document.DocumentRepository;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;
import ru.techdocs.equipment.EquipmentSource;
import ru.techdocs.equipment.EquipmentSourceRepository;
import ru.techdocs.object.FacilityRepository;
import ru.techdocs.search.SearchService;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    // UNICODE_CASE обязателен: без него CASE_INSENSITIVE не работает для кириллицы
    private static final Pattern QUANTITY_PATTERN =
            Pattern.compile("(сколько|количеств|кол-во|как много)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Set<String> STOP_WORDS = Set.of(
            "сколько", "количество", "кол-во", "как", "много", "есть", "установлено", "установлен",
            "установлена", "используется", "используются", "на", "в", "по", "объекте", "объектах",
            "объект", "системе", "система", "какие", "какое", "какой", "что", "где", "этом", "для",
            "штук", "всего", "и", "или", "ли", "документе", "документах",
            "привет", "здравствуйте", "здравствуй", "добрый", "день", "пожалуйста", "скажи",
            "подскажи", "друг", "спасибо");

    private final ChatMessageRepository messageRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final SearchService searchService;
    private final AiClient aiClient;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentSourceRepository equipmentSourceRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final FacilityRepository facilityRepository;

    public record SourceDto(Long documentId, String documentName, Integer pageNumber, Long chunkId,
                            String snippet) {}

    private static String snippet(String text) {
        if (text == null) return null;
        String stripped = text.strip().replaceAll("\\s+", " ");
        return stripped.length() > 260 ? stripped.substring(0, 260) + "…" : stripped;
    }

    public record AnswerResult(ChatMessage message, List<SourceDto> sources) {}

    @Transactional
    public AnswerResult ask(Chat chat, String question) {
        ChatMessage userMessage = new ChatMessage();
        userMessage.setChatId(chat.getId());
        userMessage.setRole("user");
        userMessage.setContent(question);
        messageRepository.save(userMessage);

        AnswerResult result;
        if (QUANTITY_PATTERN.matcher(question).find()) {
            result = answerQuantity(chat, question);
            if (result == null) {
                result = answerFromDocuments(chat, question);
            }
        } else {
            result = answerFromDocuments(chat, question);
        }

        return result;
    }

    /**
     * Количественные вопросы обрабатываются по структурированной базе оборудования,
     * а не по фрагментам документов (раздел 26 ТЗ).
     */
    private AnswerResult answerQuantity(Chat chat, String question) {
        List<String> keywords = extractKeywords(question);
        if (keywords.isEmpty()) return null;

        Map<Long, Equipment> found = new LinkedHashMap<>();
        for (String keyword : keywords) {
            for (Equipment eq : equipmentRepository.searchByTerm(
                    chat.getFacilityId(), chat.getEngineeringSystemId(), keyword)) {
                found.put(eq.getId(), eq);
            }
        }
        // Оставляем записи с максимальным числом совпавших ключевых слов:
        // "дымовых извещателей" не считает ручные извещатели (2 совпадения против 1),
        // а лишние слова в вопросе не обнуляют результат.
        Map<Long, Integer> matchCount = new LinkedHashMap<>();
        for (Equipment eq : found.values()) {
            String haystack = ((eq.getManufacturer() == null ? "" : eq.getManufacturer()) + " "
                    + (eq.getName() == null ? "" : eq.getName()) + " "
                    + (eq.getModel() == null ? "" : eq.getModel())).toLowerCase();
            matchCount.put(eq.getId(), (int) keywords.stream().filter(haystack::contains).count());
        }
        int best = matchCount.values().stream().max(Integer::compare).orElse(0);
        if (best == 0) return null;
        found.keySet().removeIf(id -> matchCount.get(id) < best);
        if (found.isEmpty()) return null;

        // группировка по модели/наименованию
        Map<String, BigDecimal> byModel = new LinkedHashMap<>();
        String unit = "шт.";
        for (Equipment eq : found.values()) {
            String label = (eq.getModel() != null && !eq.getModel().isBlank())
                    ? eq.getModel() : eq.getName();
            byModel.merge(label, eq.getQuantity(), BigDecimal::add);
            unit = eq.getUnit();
        }
        BigDecimal total = byModel.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder();
        if (chat.getFacilityId() == null) {
            // поиск по всем объектам: группируем по объекту
            sb.append("Найдено оборудование на объектах:\n\n");
            Map<Long, BigDecimal> byFacility = new LinkedHashMap<>();
            for (Equipment eq : found.values()) {
                byFacility.merge(eq.getFacilityId(), eq.getQuantity(), BigDecimal::add);
            }
            int i = 1;
            for (var entry : byFacility.entrySet()) {
                String facilityName = facilityRepository.findById(entry.getKey())
                        .map(f -> f.getName()).orElse("Объект #" + entry.getKey());
                sb.append(i++).append(". ").append(facilityName)
                        .append(" — ").append(stripZeros(entry.getValue())).append(' ').append(unit).append('\n');
            }
        } else {
            sb.append("По базе оборудования найдено ").append(stripZeros(total))
                    .append(' ').append(unit).append(":\n\n");
            for (var entry : byModel.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(" — ")
                        .append(stripZeros(entry.getValue())).append(' ').append(unit).append('\n');
            }
        }

        boolean hasUnconfirmed = found.values().stream()
                .anyMatch(eq -> !Equipment.STATUS_CONFIRMED.equals(eq.getStatus()));
        if (hasUnconfirmed) {
            sb.append("\nЧасть записей извлечена автоматически и не подтверждена пользователем — проверьте данные в реестре оборудования.");
        }

        List<SourceDto> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Equipment eq : found.values()) {
            for (EquipmentSource src : equipmentSourceRepository.findByEquipmentId(eq.getId())) {
                String key = src.getDocumentId() + ":" + src.getPageNumber();
                if (!seen.add(key)) continue;
                String docName = documentRepository.findById(src.getDocumentId())
                        .map(Document::getOriginalFilename).orElse("Документ #" + src.getDocumentId());
                sources.add(new SourceDto(src.getDocumentId(), docName, src.getPageNumber(), null,
                        snippet(src.getSourceText())));
            }
        }

        return saveAnswer(chat, sb.toString().strip(), sources);
    }

    private AnswerResult answerFromDocuments(Chat chat, String question) {
        List<SearchService.SearchHit> hits = searchService.search(
                question, chat.getFacilityId(), chat.getEngineeringSystemId(), chat.getDocumentId(), 8);

        if (hits.isEmpty()) {
            return saveAnswer(chat,
                    "В загруженных документах ответ не найден. " +
                    "Попробуйте переформулировать вопрос или загрузите дополнительную документацию.",
                    List.of());
        }

        String answer = null;
        boolean aiConfigured = aiClient.isConfigured();
        if (aiConfigured) {
            answer = askAi(question, hits);
        }
        if (answer == null) {
            String reason = aiConfigured
                    ? "ИИ-провайдер временно недоступен (проверьте, что Ollama запущен и модель загружена)"
                    : "ИИ-провайдер не настроен";
            answer = extractiveAnswer(reason, hits);
        }

        List<SourceDto> sources = new ArrayList<>();
        Set<Long> seenDocs = new HashSet<>();
        for (SearchService.SearchHit hit : hits.subList(0, Math.min(5, hits.size()))) {
            if (!seenDocs.add(hit.documentId() * 10000L + (hit.pageFrom() == null ? 0 : hit.pageFrom()))) continue;
            sources.add(new SourceDto(hit.documentId(), hit.originalFilename(), hit.pageFrom(), hit.chunkId(),
                    snippet(hit.content())));
        }
        return saveAnswer(chat, answer, sources);
    }

    private String askAi(String question, List<SearchService.SearchHit> hits) {
        StringBuilder context = new StringBuilder();
        int i = 1;
        for (SearchService.SearchHit hit : hits) {
            context.append("[Источник ").append(i++).append(": ")
                    .append(hit.originalFilename());
            if (hit.pageFrom() != null) context.append(", стр. ").append(hit.pageFrom());
            context.append("]\n").append(hit.content()).append("\n\n");
        }

        String systemPrompt = """
                Ты — ассистент по технической документации инженерных систем.
                Отвечай ТОЛЬКО на основании предоставленных фрагментов документов.
                Правила:
                1. Если в фрагментах нет ответа — прямо скажи: "В загруженных документах ответ не найден". Не придумывай.
                2. В конце ответа укажи источники в формате: "Источники:" со списком (имя файла, страница).
                3. Отвечай кратко и по делу, на русском языке.
                4. Если источники противоречат друг другу — укажи это.
                5. Числа и количества приводи ровно так, как они указаны в документах.
                   Если суммируешь несколько чисел — обязательно перепроверь сумму по шагам.
                """;
        String userPrompt = "Фрагменты документации:\n\n" + context + "\nВопрос: " + question;
        return aiClient.complete(systemPrompt, userPrompt);
    }

    private String extractiveAnswer(String reason, List<SearchService.SearchHit> hits) {
        StringBuilder sb = new StringBuilder(
                reason + ", показываю наиболее релевантные фрагменты документации:\n\n");
        int i = 1;
        for (SearchService.SearchHit hit : hits.subList(0, Math.min(3, hits.size()))) {
            String snippet = hit.content().length() > 600
                    ? hit.content().substring(0, 600) + "…" : hit.content();
            sb.append(i++).append(". ").append(hit.originalFilename());
            if (hit.pageFrom() != null) sb.append(", стр. ").append(hit.pageFrom());
            sb.append(":\n").append(snippet).append("\n\n");
        }
        return sb.toString().strip();
    }

    private AnswerResult saveAnswer(Chat chat, String content, List<SourceDto> sources) {
        ChatMessage message = new ChatMessage();
        message.setChatId(chat.getId());
        message.setRole("assistant");
        message.setContent(content);
        messageRepository.save(message);

        for (SourceDto source : sources) {
            AnswerSource answerSource = new AnswerSource();
            answerSource.setChatMessageId(message.getId());
            answerSource.setDocumentId(source.documentId());
            answerSource.setPageNumber(source.pageNumber());
            answerSource.setChunkId(source.chunkId());
            answerSourceRepository.save(answerSource);
        }
        return new AnswerResult(message, sources);
    }

    public List<SourceDto> sourcesFor(Long messageId) {
        return answerSourceRepository.findByChatMessageId(messageId).stream()
                .map(src -> new SourceDto(
                        src.getDocumentId(),
                        documentRepository.findById(src.getDocumentId())
                                .map(Document::getOriginalFilename)
                                .orElse("Документ #" + src.getDocumentId()),
                        src.getPageNumber(),
                        src.getChunkId(),
                        src.getChunkId() == null ? null
                                : chunkRepository.findById(src.getChunkId())
                                        .map(c -> snippet(c.getContent())).orElse(null)))
                .toList();
    }

    private List<String> extractKeywords(String question) {
        List<String> keywords = new ArrayList<>();
        for (String word : question.toLowerCase().split("[^\\p{L}\\p{N}-]+")) {
            String w = word.strip();
            if (w.length() < 3 || STOP_WORDS.contains(w)) continue;
            // отбрасываем окончание для простого стемминга: "извещателей" → "извещател"
            String stem = w.length() > 6 ? w.substring(0, w.length() - 2) : w;
            keywords.add(stem);
        }
        return keywords;
    }

    private String stripZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
