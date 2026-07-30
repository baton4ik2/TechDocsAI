package ru.techdocs.normative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.techdocs.ai.AiClient;
import ru.techdocs.common.BadRequestException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ИИ-подбор расценки под описание работы/оборудования.
 * <p>
 * Дёшево и качественно: тяжёлого сборника ИИ не видит. Сначала полнотекстовый
 * поиск по каталогу (бесплатно, Postgres) отбирает 25 кандидатов, и только этот
 * короткий список уходит модели (например, Gemini 2.5), которая выбирает точные
 * совпадения. Денежные показатели берутся из БД — модель лишь сопоставляет.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NormativeAiMatchService {

    private static final int CANDIDATE_LIMIT = 25;

    private final NormativeRateRepository rateRepository;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record Match(NormativeRate rate, String reason) {}

    /** Пример из эталона: как похожее оборудование этой системы уже считали. */
    public record Example(String equipment, String rateCode) {}

    /**
     * @param aiUsed       ИИ реально ответил и ответ разобран (даже если он не нашёл подходящей)
     * @param aiConfigured match-модель настроена и запрос к ней делался (для отличия «ИИ выключен»
     *                     от «ИИ включён, но не подобрал/упал» — во втором случае не подставляем
     *                     наугад дорогую расценку, а помечаем строку «на проверку»)
     */
    public record MatchResult(List<Match> matches, List<NormativeRate> candidates,
                              boolean aiUsed, boolean aiConfigured) {}

    public boolean isAvailable() {
        return aiClient.hasMatchModel();
    }

    public MatchResult match(String query) {
        return match(query, List.of(), null);
    }

    public MatchResult match(String query, List<Example> examples) {
        return match(query, examples, null);
    }

    /**
     * Сборники, допустимые для системы. Ограничение убирает ложные срабатывания
     * кросс-сборникового поиска по одному слову (фильтр трансляционной линии СОУЭ
     * не должен уходить в сборник 24 «Аэропонный комплекс»).
     */
    private static final Map<String, Set<String>> ALLOWED_BOOKS = Map.of(
            "апс", Set.of("1", "22"),
            "соуэ", Set.of("1", "22"),
            "ос", Set.of("1", "22"));

    /**
     * Подбор с few-shot примерами из эталона (как похожее оборудование этой системы уже
     * считали). Примеры сильно направляют выбор: их расценки добавляются в список
     * кандидатов (чтобы модель могла их выбрать), а в промпте прямо указано держаться
     * прецедента при отсутствии явного противопоказания.
     */
    public MatchResult match(String query, List<Example> examples, String system) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("Опишите работу или оборудование для подбора расценки.");
        }
        boolean configured = aiClient.hasMatchModel();
        List<NormativeRate> candidates = new ArrayList<>(rateRepository.search(query.strip(), CANDIDATE_LIMIT));
        restrictToAllowedBooks(candidates, system);
        // расценки из эталонных примеров добавляем в кандидаты — иначе модель не сможет их выбрать
        mergeExampleRates(candidates, examples);
        if (candidates.isEmpty()) {
            return new MatchResult(List.of(), List.of(), false, configured);
        }
        if (!configured) {
            // без ИИ возвращаем кандидатов из полнотекстового поиска как есть
            return new MatchResult(List.of(), candidates, false, false);
        }

        String answer = aiClient.completeMatch(systemPrompt(), userPrompt(query, candidates, examples));
        if (answer == null || answer.isBlank()) {
            // ИИ настроен, но запрос упал/пуст (ошибка провайдера уже в логах AiClient)
            return new MatchResult(List.of(), candidates, false, true);
        }

        List<Match> matches = parse(answer, candidates);
        return new MatchResult(matches, candidates, true, true);
    }

    /**
     * Оставляет кандидатов только из сборников, допустимых для системы. Если после
     * фильтра не осталось ничего — ограничение снимается (лучше показать что-то, чем
     * ничего), строку всё равно проверит инженер.
     */
    private void restrictToAllowedBooks(List<NormativeRate> candidates, String system) {
        String canonical = ru.techdocs.common.SystemNormalizer.canonical(system);
        if (canonical == null || candidates.isEmpty()) return;   // Map.of не принимает null-ключ
        Set<String> allowed = ALLOWED_BOOKS.get(canonical);
        if (allowed == null) return;
        List<NormativeRate> filtered = candidates.stream()
                .filter(r -> allowed.contains(bookOf(r.getCode())))
                .toList();
        if (!filtered.isEmpty()) {
            candidates.clear();
            candidates.addAll(filtered);
        }
    }

    /** Номер сборника из шифра расценки: «22-2203-91-1/1» → «22». */
    private static String bookOf(String code) {
        if (code == null) return "";
        int dash = code.indexOf('-');
        return dash > 0 ? code.substring(0, dash) : code;
    }

    /** Добавляет в список кандидатов расценки из эталонных примеров (без дублей). */
    private void mergeExampleRates(List<NormativeRate> candidates, List<Example> examples) {
        if (examples == null || examples.isEmpty()) return;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (NormativeRate r : candidates) have.add(r.getCode());
        for (Example ex : examples) {
            if (ex.rateCode() == null || have.contains(ex.rateCode())) continue;
            rateRepository.findFirstByCodeOrderById(ex.rateCode()).ifPresent(r -> {
                candidates.add(r);
                have.add(r.getCode());
            });
        }
    }

    private String systemPrompt() {
        return """
                Ты — инженер-сметчик по обслуживанию инженерных систем зданий.
                Тебе дают описание оборудования, примеры из эталонных смет (как похожее
                оборудование этой же системы уже считали) и пронумерованный список
                расценок СН-2012. Выбери из списка расценки, которые ТОЧНО подходят.
                Правила:
                - СИЛЬНО опирайся на эталонные примеры: если в них есть такое же или
                  явно однотипное оборудование, выбери ТУ ЖЕ расценку, если нет прямого
                  противопоказания по описанию;
                - выбирай только расценки из списка, не придумывай новые шифры;
                - если подходящих нет — верни пустой массив;
                - сортируй от самой релевантной к менее релевантной;
                - в reason коротко (до 12 слов) поясни выбор (укажи, если по эталону).
                Ответ — строго JSON-массив объектов {"code": "...", "reason": "..."},
                без пояснений вне JSON.
                """;
    }

    private String userPrompt(String query, List<NormativeRate> candidates, List<Example> examples) {
        StringBuilder sb = new StringBuilder();
        if (examples != null && !examples.isEmpty()) {
            sb.append("Примеры из эталона (оборудование → расценка):\n");
            for (Example ex : examples) {
                sb.append("- ").append(ex.equipment()).append(" → ").append(ex.rateCode()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Описание: ").append(query.strip()).append("\n\nРасценки:\n");
        for (NormativeRate r : candidates) {
            sb.append("- ").append(r.getCode()).append(" | ").append(r.getName());
            if (r.getUnit() != null && !r.getUnit().isBlank()) {
                sb.append(" | изм.: ").append(r.getUnit());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private List<Match> parse(String answer, List<NormativeRate> candidates) {
        Map<String, NormativeRate> byCode = new LinkedHashMap<>();
        for (NormativeRate r : candidates) {
            byCode.putIfAbsent(r.getCode(), r);
        }
        List<Match> matches = new ArrayList<>();
        try {
            int start = answer.indexOf('[');
            int end = answer.lastIndexOf(']');
            if (start < 0 || end <= start) return matches;
            JsonNode array = objectMapper.readTree(answer.substring(start, end + 1));
            for (JsonNode node : array) {
                String code = text(node, "code");
                if (code == null) continue;
                NormativeRate rate = byCode.get(code.strip());
                if (rate == null) continue; // модель вернула шифр не из списка — отбрасываем
                matches.add(new Match(rate, text(node, "reason")));
            }
        } catch (Exception e) {
            log.warn("Не удалось разобрать ответ ИИ-подбора расценки: {}", e.getMessage());
        }
        return matches;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
