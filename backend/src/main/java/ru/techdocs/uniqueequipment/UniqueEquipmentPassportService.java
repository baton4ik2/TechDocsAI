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
import ru.techdocs.config.AppProperties;
import ru.techdocs.processing.OcrExtractor;
import ru.techdocs.processing.PageImageRenderer;
import ru.techdocs.processing.PageText;
import ru.techdocs.processing.TextExtractor;
import ru.techdocs.storage.FileStorage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Паспорт уникального оборудования: загрузка и извлечение плановых работ ИИ.
 * Из текста паспорта модель выделяет регламентные работы (осмотр, ТО, контроль
 * функционирования …) с периодичностью — они становятся плановыми работами
 * оборудования (источник PASSPORT) и приоритетнее ПКМ при расчёте смет.
 *
 * <p>Модель обязана приводить дословную цитату и страницу. Цитата ищется в
 * тексте паспорта: найдена — работа подтверждена и страница берётся из текста,
 * не найдена — работа сохраняется с пометкой «не подтверждена». Так придуманная
 * периодичность видна инженеру, а не растворяется в списке.
 *
 * <p>Скан больше не тупик: сначала пробуем локальный OCR (бесплатно), и только
 * если его нет — vision-модель по картинкам страниц.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UniqueEquipmentPassportService {

    private static final int MAX_CHARS = 12000;
    /** Сколько страниц скана отдаём vision-модели: каждая страница — отдельный платный запрос. */
    private static final int MAX_VISION_PAGES = 12;

    public static final String MODE_TEXT = "TEXT";
    public static final String MODE_OCR = "OCR";
    public static final String MODE_VISION = "VISION";

    private final UniqueEquipmentRepository repository;
    private final PlannedWorkRepository plannedWorkRepository;
    private final FileStorage fileStorage;
    private final TextExtractor textExtractor;
    private final OcrExtractor ocrExtractor;
    private final PageImageRenderer pageImageRenderer;
    private final AiClient aiClient;
    private final AppProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Модели, между которыми можно переключаться в интерфейсе, и модель по умолчанию. */
    public record PassportModels(List<String> models, String defaultModel, boolean aiConfigured) {}

    public PassportModels models() {
        String def = aiClient.defaultPassportModel();
        Set<String> list = new LinkedHashSet<>();
        if (def != null && !def.isBlank()) list.add(def.strip());
        String configured = props.ai().passportModels();
        if (configured != null && !configured.isBlank()) {
            for (String m : configured.split(",")) {
                if (!m.isBlank()) list.add(m.strip());
            }
        }
        return new PassportModels(List.copyOf(list), def, aiClient.hasPassportModel());
    }

    public UniqueEquipment upload(Long equipmentId, MultipartFile file, String model) {
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
        processAsync(equipmentId, model);
        return ue;
    }

    @Async("documentProcessingExecutor")
    public void processAsync(Long equipmentId, String model) {
        process(equipmentId, model);
    }

    public void process(Long equipmentId, String requestedModel) {
        UniqueEquipment ue = repository.findById(equipmentId).orElse(null);
        if (ue == null || ue.getPassportStoragePath() == null) return;

        PassportModels available = models();
        String model = requestedModel == null || requestedModel.isBlank()
                ? available.defaultModel() : requestedModel.strip();
        if (requestedModel != null && !requestedModel.isBlank()
                && !available.models().contains(model)) {
            finishError(ue, "Модель «" + model + "» не разрешена. Добавьте её в AI_PASSPORT_MODELS.");
            return;
        }

        ue.setPassportStatus(UniqueEquipment.PASSPORT_PROCESSING);
        ue.setPassportError(null);
        ue.setPassportModel(model);
        repository.save(ue);
        try {
            if (!aiClient.hasPassportModel() && !aiClient.hasVisionModel()) {
                finishError(ue, "ИИ-провайдер не настроен — добавьте плановые работы вручную.");
                return;
            }

            byte[] bytes;
            try (InputStream in = fileStorage.load(ue.getPassportStoragePath())) {
                bytes = in.readAllBytes();
            }

            List<PageText> pages;
            String mode = MODE_TEXT;
            TextExtractor.ExtractionResult res =
                    textExtractor.extract(ue.getPassportFilename(), null, new ByteArrayInputStream(bytes));
            pages = res.pages();
            if (res.needsOcr()) {
                // скан: сначала локальный OCR — он бесплатный и даёт текст для сверки цитат
                if (ocrExtractor.isAvailable()) {
                    pages = ocrExtractor.extract(ue.getPassportFilename(), new ByteArrayInputStream(bytes));
                    mode = MODE_OCR;
                } else if (aiClient.hasVisionModel()) {
                    mode = MODE_VISION;
                } else {
                    finishError(ue, "Паспорт — скан, а распознавание текста и vision-модель недоступны. "
                            + "Добавьте плановые работы вручную.");
                    return;
                }
            }

            List<PlannedWork> works = MODE_VISION.equals(mode)
                    ? extractFromScan(equipmentId, ue.getPassportFilename(), bytes)
                    : extractFromText(equipmentId, pages, model);

            // заменяем ранее извлечённые из паспорта работы (ручные не трогаем)
            plannedWorkRepository.deleteByUniqueEquipmentIdAndSource(equipmentId, PlannedWork.SOURCE_PASSPORT);
            plannedWorkRepository.saveAll(works);

            ue.setPassportStatus(UniqueEquipment.PASSPORT_READY);
            ue.setPassportMode(mode);
            ue.setPassportError(summary(works, mode));
            repository.save(ue);
            log.info("Паспорт оборудования {} ({}, {}): извлечено {} плановых работ",
                    equipmentId, mode, model, works.size());
        } catch (Throwable e) {
            log.error("Ошибка обработки паспорта {}: {}", equipmentId, e.getMessage(), e);
            finishError(ue, "Не удалось обработать паспорт (" + e.getClass().getSimpleName() + ").");
        }
    }

    /** Итог разбора для карточки паспорта: пусто — всё чисто, показывать нечего. */
    private String summary(List<PlannedWork> works, String mode) {
        if (works.isEmpty()) return "Плановые работы в паспорте не распознаны — добавьте вручную.";
        long unverified = works.stream().filter(w -> Boolean.FALSE.equals(w.getQuoteVerified())).count();
        if (unverified == 0) return null;
        return unverified + " из " + works.size() + " работ не подтверждены цитатой из паспорта — проверьте.";
    }

    // ---------- текстовый путь ----------

    private List<PlannedWork> extractFromText(Long equipmentId, List<PageText> pages, String model) {
        Document doc = Document.of(pages);
        if (doc.text().isBlank()) return List.of();
        String snippet = doc.relevantWindow(MAX_CHARS);
        String answer = aiClient.completePassport(systemPrompt(), snippet, model);
        return parse(equipmentId, answer, doc, null);
    }

    // ---------- путь скана: страницы читает vision-модель ----------

    private List<PlannedWork> extractFromScan(Long equipmentId, String filename, byte[] bytes) {
        int pageCount = pageCount(filename, bytes);
        int limit = Math.min(pageCount, MAX_VISION_PAGES);
        List<PlannedWork> works = new ArrayList<>();
        int visionDpi = 200;
        for (int page = 1; page <= limit; page++) {
            String answer;
            try {
                byte[] png = pageImageRenderer.renderPng(filename, new ByteArrayInputStream(bytes), page, visionDpi);
                answer = aiClient.completeVision(systemPrompt(),
                        "Страница " + page + " паспорта. Верни JSON-массив по инструкции.", png);
            } catch (Exception e) {
                log.warn("Паспорт {}: страница {} не разобрана vision-моделью: {}",
                        equipmentId, page, e.getMessage());
                continue;
            }
            // сверять цитату не с чем — текста нет; страница известна точно, её и ставим
            works.addAll(parse(equipmentId, answer, null, page));
        }
        renumber(works);
        return works;
    }

    private int pageCount(String filename, byte[] bytes) {
        if (!filename.toLowerCase().endsWith(".pdf")) return 1;
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(
                new org.apache.pdfbox.io.RandomAccessReadBuffer(new ByteArrayInputStream(bytes)))) {
            return pdf.getNumberOfPages();
        } catch (Exception e) {
            return 1;
        }
    }

    // ---------- разбор ответа модели ----------

    private List<PlannedWork> parse(Long equipmentId, String answer, Document doc, Integer forcedPage) {
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

                String quote = text(node, "цитата", "quote");
                w.setSourceQuote(quote);
                if (forcedPage != null) {
                    // страница скана известна точно, сверять цитату не с чем
                    w.setSourcePage(forcedPage);
                    w.setQuoteVerified(null);
                } else if (doc != null) {
                    int at = quote == null ? -1 : doc.locate(quote);
                    w.setQuoteVerified(at >= 0);
                    w.setSourcePage(at >= 0 ? doc.pageOf(at) : intOrNull(node, "страница", "page"));
                }
                works.add(w);
            }
        } catch (Exception e) {
            log.warn("Не удалось разобрать ответ ИИ по паспорту {}: {}", equipmentId, e.getMessage());
        }
        return works;
    }

    private void renumber(List<PlannedWork> works) {
        int pos = 1;
        for (PlannedWork w : works) w.setPosition(pos++);
    }

    private String systemPrompt() {
        return """
                Ты — инженер по эксплуатации. Из текста паспорта/руководства оборудования
                выдели ПЛАНОВЫЕ (регламентные) работы по обслуживанию: осмотр, техническое
                обслуживание, контроль функционирования, проверка и т.п.

                Жёсткие правила:
                1. Бери только то, что есть в тексте. Ничего не додумывай: если периодичность
                   не указана — оставь поле "периодичность" пустым, не подставляй типовую.
                2. На каждую работу приведи в поле "цитата" ДОСЛОВНЫЙ фрагмент исходного
                   текста (10–200 символов), из которого она следует, без пересказа и правок.
                   В поле "страница" — номер страницы из пометки [Страница N] над фрагментом.
                3. Не выдумывай работы «по умолчанию». Если регламентных работ в тексте нет —
                   верни пустой массив.
                4. Гарантийные обязательства, условия хранения, транспортировки и монтаж —
                   это не плановые работы, их не включай.

                Ответ — строго JSON-массив объектов
                {"тип": "...", "наименование": "...", "периодичность": "...", "состав": "...",
                 "цитата": "...", "страница": 1}
                без пояснений вне JSON.
                """;
    }

    /**
     * Текст паспорта с разметкой страниц: нужен, чтобы найти цитату модели и
     * назвать страницу, на которой она стоит.
     */
    public record Document(String text, int[] pageStarts, int[] pageNumbers,
                           String norm, int[] normToOrig) {

        public static Document of(List<PageText> pages) {
            StringBuilder sb = new StringBuilder();
            List<Integer> starts = new ArrayList<>();
            List<Integer> numbers = new ArrayList<>();
            for (PageText p : pages) {
                if (p.text() == null || p.text().isBlank()) continue;
                starts.add(sb.length());
                numbers.add(p.pageNumber());
                sb.append("[Страница ").append(p.pageNumber()).append("]\n")
                        .append(p.text()).append('\n');
            }
            String text = sb.toString();
            // нормализованная копия с картой позиций: сравнение цитаты не должно
            // спотыкаться о регистр, переносы строк и двойные пробелы
            StringBuilder norm = new StringBuilder(text.length());
            int[] map = new int[text.length()];
            boolean lastSpace = true;
            for (int i = 0; i < text.length(); i++) {
                char c = Character.toLowerCase(text.charAt(i));
                if (Character.isWhitespace(c)) {
                    if (lastSpace) continue;
                    c = ' ';
                    lastSpace = true;
                } else {
                    lastSpace = false;
                }
                map[norm.length()] = i;
                norm.append(c);
            }
            return new Document(text, toArray(starts), toArray(numbers), norm.toString(), map);
        }

        private static int[] toArray(List<Integer> list) {
            int[] a = new int[list.size()];
            for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
            return a;
        }

        /** Позиция цитаты в исходном тексте или -1, если её там нет. */
        public int locate(String quote) {
            String needle = normalize(quote);
            if (needle.length() < 8) return -1;
            int at = norm.indexOf(needle);
            if (at >= 0) return normToOrig[at];
            return fuzzy(needle);
        }

        /**
         * Запасной поиск: OCR путает буквы, и дословное вхождение может не совпасть.
         * Считаем цитату найденной, если в окне текста стоит хотя бы 70% её слов.
         */
        private int fuzzy(String needle) {
            List<String> tokens = new ArrayList<>();
            for (String t : needle.split(" ")) if (t.length() >= 4) tokens.add(t);
            if (tokens.size() < 3) return -1;
            int window = Math.max(needle.length() * 2, 200);
            int required = (int) Math.ceil(tokens.size() * 0.7);
            // якорем служит любое слово цитаты: испорченным может оказаться и первое
            int checks = 0;
            for (String anchor : tokens) {
                for (int at = norm.indexOf(anchor); at >= 0; at = norm.indexOf(anchor, at + 1)) {
                    if (++checks > 200) return -1;
                    int from = Math.max(0, at - window / 2);
                    String slice = norm.substring(from, Math.min(norm.length(), from + window));
                    int hits = 0;
                    for (String t : tokens) if (slice.contains(t)) hits++;
                    if (hits >= required) return normToOrig[from];
                }
            }
            return -1;
        }

        public int pageOf(int offset) {
            int page = pageNumbers.length == 0 ? 1 : pageNumbers[0];
            for (int i = 0; i < pageStarts.length; i++) {
                if (pageStarts[i] <= offset) page = pageNumbers[i];
                else break;
            }
            return page;
        }

        /** Окно текста вокруг разделов об обслуживании — экономит токены. */
        public String relevantWindow(int maxChars) {
            if (text.length() <= maxChars) return text;
            String lower = text.toLowerCase();
            for (String kw : List.of("регламент", "обслуживан", "периодичн", "техническое обслуж")) {
                int idx = lower.indexOf(kw);
                if (idx >= 0) {
                    int from = Math.max(0, idx - 500);
                    return text.substring(from, Math.min(text.length(), from + maxChars));
                }
            }
            return text.substring(0, maxChars);
        }

        private static String normalize(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            boolean lastSpace = true;
            for (int i = 0; i < s.length(); i++) {
                char c = Character.toLowerCase(s.charAt(i));
                if (Character.isWhitespace(c)) {
                    if (lastSpace) continue;
                    sb.append(' ');
                    lastSpace = true;
                } else {
                    sb.append(c);
                    lastSpace = false;
                }
            }
            return sb.toString().strip();
        }
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

    private Integer intOrNull(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isNumber()) return v.asInt();
            if (v != null && v.isTextual() && v.asText().strip().matches("\\d{1,4}")) {
                return Integer.parseInt(v.asText().strip());
            }
        }
        return null;
    }

    private String sanitize(String filename) {
        return filename.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }
}
