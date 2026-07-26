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
        if (query == null || query.isBlank()) {
            throw new BadRequestException("Опишите работу или оборудование для подбора расценки.");
        }
        boolean configured = aiClient.hasMatchModel();
        List<NormativeRate> candidates = rateRepository.search(query.strip(), CANDIDATE_LIMIT);
        if (candidates.isEmpty()) {
            return new MatchResult(List.of(), List.of(), false, configured);
        }
        if (!configured) {
            // без ИИ возвращаем кандидатов из полнотекстового поиска как есть
            return new MatchResult(List.of(), candidates, false, false);
        }

        String answer = aiClient.completeMatch(systemPrompt(), userPrompt(query, candidates));
        if (answer == null || answer.isBlank()) {
            // ИИ настроен, но запрос упал/пуст (ошибка провайдера уже в логах AiClient)
            return new MatchResult(List.of(), candidates, false, true);
        }

        List<Match> matches = parse(answer, candidates);
        return new MatchResult(matches, candidates, true, true);
    }

    private String systemPrompt() {
        return """
                Ты — инженер-сметчик по обслуживанию инженерных систем зданий.
                Тебе дают описание работы или оборудования и пронумерованный список
                расценок СН-2012. Выбери из списка расценки, которые ТОЧНО подходят
                под описание (техобслуживание, осмотр, ремонт — учитывай тип работы).
                Правила:
                - выбирай только расценки из списка, не придумывай новые шифры;
                - если подходящих нет — верни пустой массив;
                - сортируй от самой релевантной к менее релевантной;
                - в reason коротко (до 12 слов) поясни, почему расценка подходит.
                Ответ — строго JSON-массив объектов {"code": "...", "reason": "..."},
                без пояснений вне JSON.
                """;
    }

    private String userPrompt(String query, List<NormativeRate> candidates) {
        StringBuilder sb = new StringBuilder("Описание: ").append(query.strip()).append("\n\nРасценки:\n");
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
