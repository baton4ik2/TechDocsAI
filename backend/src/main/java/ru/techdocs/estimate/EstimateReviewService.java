package ru.techdocs.estimate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.techdocs.ai.AiClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Проверка готовой сметы сильной моделью: один проход по всем строкам вместе с
 * эталоном системы и правилами методики. Правила ловят то, что мы уже знаем;
 * эта проверка — то, для чего правило не написано (пропущенная работа, аналог не
 * из того сборника, противоречие между строками).
 * <p>
 * Модель НЕ меняет смету: она возвращает только замечания со ссылкой на строку.
 * Все решения принимает инженер.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EstimateReviewService {

    private final EstimateService estimateService;
    private final EstimateRowRepository rowRepository;
    private final EstimateDecisionService decisionService;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Сколько эталонных решений подкладывать на систему — контекст, а не весь эталон. */
    private static final int ETALON_LIMIT = 40;

    /**
     * @param position   номер строки сметы (null — замечание по смете в целом)
     * @param severity   HIGH / MEDIUM / LOW
     * @param title      суть в одной строке
     * @param detail     почему это проблема и что проверить
     * @param impact     влияние на деньги словами, если модель смогла оценить
     */
    public record Finding(Integer position, String severity, String category,
                          String title, String detail, String impact) {}

    public record ReviewResult(List<Finding> findings, boolean aiConfigured, String error) {}

    public boolean isAvailable() {
        return aiClient.hasReviewModel();
    }

    public ReviewResult review(Long estimateId) {
        Estimate estimate = estimateService.get(estimateId);
        List<EstimateRow> rows = rowRepository.findByEstimateIdOrderByPosition(estimateId);
        if (rows.isEmpty()) {
            return new ReviewResult(List.of(), isAvailable(), "В смете нет строк.");
        }
        if (!isAvailable()) {
            return new ReviewResult(List.of(), false,
                    "Модель проверки не настроена: задайте AI_REVIEW_MODEL (и при необходимости "
                            + "AI_REVIEW_BASE_URL, AI_REVIEW_API_KEY).");
        }

        String answer;
        try {
            answer = aiClient.completeReview(systemPrompt(), userPrompt(estimate, rows));
        } catch (Exception e) {
            log.warn("Проверка сметы {} не удалась: {}", estimateId, e.getMessage());
            return new ReviewResult(List.of(), true, "Модель не ответила: " + e.getClass().getSimpleName());
        }
        if (answer == null || answer.isBlank()) {
            return new ReviewResult(List.of(), true, "Модель вернула пустой ответ.");
        }
        return new ReviewResult(parse(answer, rows), true, null);
    }

    private String systemPrompt() {
        return """
                Ты — сметчик-эксперт по обслуживанию систем безопасности, проверяешь готовую
                смету по сборникам СН-2012 перед сдачей заказчику.

                Твоя задача — найти ошибки и пропуски. Ты НЕ правишь смету и НЕ считаешь
                деньги заново: ты только перечисляешь замечания.

                На что смотреть:
                1. Пропущенные работы. У оборудования обычно есть пара «осмотр + техническое
                   обслуживание». Если для позиции взят только осмотр — вероятно, ТО потеряно.
                2. Расценка не по оборудованию. Для АПС и СОУЭ допустимы Сборники 1 и 22;
                   расценка из другого сборника или на явно другое изделие — ошибка.
                3. Противоречия. Периодичность и количество операций в год должны быть
                   согласованы. Меньшее число операций допустимо, когда частая работа
                   поглощена более редкой (осмотр входит в ТО) — это норма, но должно быть
                   объяснено в самой строке.
                4. Нулевые и пустые строки: без расценки, без периодичности, с нулевым
                   количеством — такие позиции дают ноль и не должны попадать в смету молча.
                5. Несогласованность: одинаковое оборудование в разных строках получило
                   разные расценки или разную периодичность без причины.
                6. Отклонения от эталона: если в эталоне для этого оборудования другая
                   расценка, это повод для замечания.

                Чего НЕ делать:
                - не выдумывать шифры расценок, которых нет в присланных данных;
                - не предлагать «уточнить» и «проверить» без конкретной причины;
                - не повторять одно и то же замечание для каждой строки — сгруппируй.

                Ответ — СТРОГО JSON без пояснений вокруг:
                {"findings":[{"position":12,"severity":"HIGH","category":"пропущенная работа",
                "title":"кратко","detail":"почему это проблема и что проверить",
                "impact":"влияние на деньги словами или пусто"}]}
                position — номер строки сметы, null для замечания по смете в целом.
                severity: HIGH — влияет на деньги или сдачу; MEDIUM — вероятная ошибка;
                LOW — оформление. Если замечаний нет, верни {"findings":[]}.
                """;
    }

    private String userPrompt(Estimate estimate, List<EstimateRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("Смета: ").append(estimate.getName()).append('\n');
        sb.append("Коэффициент перехода в уровень РТ: ").append(estimate.getRtCoefficient()).append("\n\n");

        sb.append("СТРОКИ СМЕТЫ (№ | раздел | оборудование | модель | мероприятие | шифр | "
                + "периодичность | операций в год | количество | ЗП за единицу):\n");
        for (EstimateRow r : rows) {
            sb.append(r.getPosition()).append(" | ").append(nz(r.getSection()))
                    .append(" | ").append(nz(r.getEquipmentName()))
                    .append(" | ").append(nz(r.getEquipmentType()))
                    .append(" | ").append(nz(r.getOperationName()))
                    .append(" | ").append(nz(r.getRateCode()))
                    .append(" | ").append(nz(r.getPeriodicity()))
                    .append(" | ").append(nz(r.getOpsPerYear()))
                    .append(" | ").append(nz(r.getQty()))
                    .append(" | ").append(nz(r.getPriceZp()))
                    .append('\n');
        }

        // эталон систем, встречающихся в смете: с чем модель сравнивает выбор
        Set<String> systems = new LinkedHashSet<>();
        for (EstimateRow r : rows) if (r.getSection() != null) systems.add(r.getSection());
        StringBuilder etalon = new StringBuilder();
        for (String system : systems) {
            var data = decisionService.systemEtalon(system, ETALON_LIMIT);
            for (var entry : data.entries()) {
                etalon.append(system).append(" | ").append(entry.name())
                        .append(" | ").append(entry.rate().rateCode())
                        .append(" | ").append(nz(entry.rate().periodicity())).append('\n');
            }
        }
        if (!etalon.isEmpty()) {
            sb.append("\nЭТАЛОН (система | оборудование | шифр | периодичность) — "
                    + "как это же оборудование считали раньше:\n").append(etalon);
        }
        sb.append("\nНайди замечания и верни JSON.");
        return sb.toString();
    }

    /** Разбор ответа модели; номера строк проверяем по смете, выдуманные отбрасываем. */
    private List<Finding> parse(String answer, List<EstimateRow> rows) {
        Set<Integer> positions = new LinkedHashSet<>();
        for (EstimateRow r : rows) if (r.getPosition() != null) positions.add(r.getPosition());

        List<Finding> findings = new ArrayList<>();
        try {
            String json = answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1);
            JsonNode root = objectMapper.readTree(json);
            for (JsonNode node : root.path("findings")) {
                Integer position = node.path("position").isIntegralNumber()
                        ? node.path("position").asInt() : null;
                // номер вне сметы — замечание сохраняем, но не привязываем к строке
                if (position != null && !positions.contains(position)) position = null;
                String title = node.path("title").asText("").strip();
                if (title.isEmpty()) continue;
                findings.add(new Finding(position,
                        severity(node.path("severity").asText("MEDIUM")),
                        node.path("category").asText("").strip(),
                        title,
                        node.path("detail").asText("").strip(),
                        node.path("impact").asText("").strip()));
            }
        } catch (Exception e) {
            log.warn("Не удалось разобрать ответ проверки сметы: {}", e.getMessage());
        }
        return findings;
    }

    private String severity(String raw) {
        String s = raw == null ? "" : raw.strip().toUpperCase();
        return switch (s) {
            case "HIGH", "MEDIUM", "LOW" -> s;
            default -> "MEDIUM";
        };
    }

    private String nz(String s) {
        return s == null || s.isBlank() ? "—" : s.strip();
    }

    private String nz(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }
}
