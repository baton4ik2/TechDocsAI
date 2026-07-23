package ru.techdocs.uniqueequipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.techdocs.ai.AiClient;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.Periodicity;
import ru.techdocs.processing.PageText;
import ru.techdocs.processing.TextExtractor;
import ru.techdocs.storage.FileStorage;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Паспорт уникального оборудования: загрузка и извлечение плановых работ ИИ.
 * Из текста паспорта модель выделяет регламентные работы (осмотр, ТО, контроль
 * функционирования …) с периодичностью — они становятся плановыми работами
 * оборудования (источник PASSPORT) и приоритетнее ПКМ при расчёте смет.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UniqueEquipmentPassportService {

    private static final int MAX_CHARS = 9000;

    private final UniqueEquipmentRepository repository;
    private final PlannedWorkRepository plannedWorkRepository;
    private final FileStorage fileStorage;
    private final TextExtractor textExtractor;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UniqueEquipment upload(Long equipmentId, MultipartFile file) {
        UniqueEquipment ue = repository.findById(equipmentId)
                .orElseThrow(() -> new BadRequestException("Оборудование не найдено"));
        String filename = file.getOriginalFilename() == null ? "passport.pdf" : file.getOriginalFilename();
        String path = "passports/" + equipmentId + "/" + UUID.randomUUID() + "-" + sanitize(filename);
        try (InputStream in = file.getInputStream()) {
            fileStorage.save(path, in, file.getSize(), file.getContentType());
        } catch (Exception e) {
            throw new BadRequestException("Не удалось сохранить файл: " + e.getMessage());
        }
        ue.setPassportFilename(filename);
        ue.setPassportStoragePath(path);
        ue.setPassportStatus(UniqueEquipment.PASSPORT_UPLOADED);
        ue.setPassportError(null);
        repository.save(ue);
        processAsync(equipmentId);
        return ue;
    }

    @Async("documentProcessingExecutor")
    public void processAsync(Long equipmentId) {
        process(equipmentId);
    }

    public void process(Long equipmentId) {
        UniqueEquipment ue = repository.findById(equipmentId).orElse(null);
        if (ue == null || ue.getPassportStoragePath() == null) return;

        ue.setPassportStatus(UniqueEquipment.PASSPORT_PROCESSING);
        ue.setPassportError(null);
        repository.save(ue);
        try {
            String text;
            try (InputStream in = fileStorage.load(ue.getPassportStoragePath())) {
                TextExtractor.ExtractionResult res = textExtractor.extract(ue.getPassportFilename(), null, in);
                if (res.needsOcr()) {
                    finishError(ue, "Паспорт — скан. Распознайте текст (OCR) или добавьте плановые работы вручную.");
                    return;
                }
                text = join(res.pages());
            }
            if (!aiClient.hasMatchModel() && !aiClient.isConfigured()) {
                finishError(ue, "ИИ-провайдер не настроен — добавьте плановые работы вручную.");
                return;
            }
            List<PlannedWork> works = extract(equipmentId, text);
            // заменяем ранее извлечённые из паспорта работы (ручные не трогаем)
            plannedWorkRepository.deleteByUniqueEquipmentIdAndSource(equipmentId, PlannedWork.SOURCE_PASSPORT);
            plannedWorkRepository.saveAll(works);

            ue.setPassportStatus(UniqueEquipment.PASSPORT_READY);
            if (works.isEmpty()) {
                ue.setPassportError("Плановые работы в паспорте не распознаны — добавьте вручную.");
            }
            repository.save(ue);
            log.info("Паспорт оборудования {}: извлечено {} плановых работ", equipmentId, works.size());
        } catch (Throwable e) {
            log.error("Ошибка обработки паспорта {}: {}", equipmentId, e.getMessage(), e);
            finishError(ue, "Не удалось обработать паспорт (" + e.getClass().getSimpleName() + ").");
        }
    }

    private List<PlannedWork> extract(Long equipmentId, String text) {
        String snippet = relevantWindow(text);
        String answer = aiClient.hasMatchModel()
                ? aiClient.completeMatch(systemPrompt(), snippet)
                : aiClient.complete(systemPrompt(), snippet);
        if (answer == null || answer.isBlank()) return List.of();

        List<PlannedWork> works = new ArrayList<>();
        try {
            int start = answer.indexOf('[');
            int end = answer.lastIndexOf(']');
            if (start < 0 || end <= start) return List.of();
            JsonNode arr = objectMapper.readTree(answer.substring(start, end + 1));
            int pos = 1;
            for (JsonNode node : arr) {
                String name = text(node, "наименование", "работа", "name");
                if (name == null || name.isBlank()) continue;
                PlannedWork w = new PlannedWork();
                w.setUniqueEquipmentId(equipmentId);
                w.setPosition(pos++);
                w.setWorkType(text(node, "тип", "вид", "type"));
                w.setName(name);
                w.setWorkComposition(text(node, "состав", "описание"));
                String periodicity = text(node, "периодичность", "период");
                w.setPeriodicity(periodicity);
                w.setPeriodicityPerYear(Periodicity.perYear(periodicity));
                w.setSource(PlannedWork.SOURCE_PASSPORT);
                works.add(w);
            }
        } catch (Exception e) {
            log.warn("Не удалось разобрать ответ ИИ по паспорту {}: {}", equipmentId, e.getMessage());
        }
        return works;
    }

    private String systemPrompt() {
        return """
                Ты — инженер по эксплуатации. Из текста паспорта/руководства оборудования
                выдели ПЛАНОВЫЕ (регламентные) работы по обслуживанию: осмотр, техническое
                обслуживание, контроль функционирования, проверка и т.п. Для каждой укажи
                периодичность, если она есть в тексте.
                Ответ — строго JSON-массив объектов
                {"тип": "...", "наименование": "...", "периодичность": "...", "состав": "..."},
                без пояснений вне JSON. Если работ нет — пустой массив.
                """;
    }

    /** Окно текста вокруг разделов об обслуживании — экономит токены. */
    private String relevantWindow(String text) {
        if (text.length() <= MAX_CHARS) return text;
        String lower = text.toLowerCase();
        for (String kw : List.of("регламент", "обслуживан", "периодичн", "техническое обслуж")) {
            int idx = lower.indexOf(kw);
            if (idx >= 0) {
                int from = Math.max(0, idx - 500);
                return text.substring(from, Math.min(text.length(), from + MAX_CHARS));
            }
        }
        return text.substring(0, MAX_CHARS);
    }

    private String join(List<PageText> pages) {
        StringBuilder sb = new StringBuilder();
        for (PageText p : pages) if (p.text() != null) sb.append(p.text()).append('\n');
        return sb.toString();
    }

    private void finishError(UniqueEquipment ue, String message) {
        ue.setPassportStatus(UniqueEquipment.PASSPORT_ERROR);
        ue.setPassportError(message);
        repository.save(ue);
    }

    private String text(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText().strip();
        }
        return null;
    }

    private String sanitize(String filename) {
        return filename.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }
}
