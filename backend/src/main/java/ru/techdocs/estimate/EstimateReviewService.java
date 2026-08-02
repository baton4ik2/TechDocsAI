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
    private final EstimateReviewRepository reviewRepository;
    private final ru.techdocs.config.AppProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Сколько эталонных решений подкладывать на систему — контекст, а не весь эталон. */
    private static final int ETALON_LIMIT = 40;

    /**
     * Конкретная правка, которую можно применить к смете. Есть не у каждого
     * замечания: «проверьте принадлежность позиции к предмету договора» — это
     * решение инженера, а не действие приложения.
     *
     * @param action SET_RATE / SET_PERIODICITY / SET_QTY / SET_OPERATION_NAME / ADD_ROW
     */
    public record Fix(String action, String rateCode, String periodicity,
                      BigDecimal opsPerYear, BigDecimal qty, String operationName) {}

    /**
     * @param position номер строки сметы (null — замечание по смете в целом)
     * @param severity HIGH / MEDIUM / LOW
     * @param detail   почему это проблема и что проверить
     * @param impact   влияние на деньги словами, если модель смогла оценить
     * @param fix      предложенная правка или null, если решать инженеру
     * @param applied  правка уже применена — повторно не предлагаем
     */
    public record Finding(Integer position, String severity, String category,
                          String title, String detail, String impact,
                          Fix fix, boolean applied) {}

    /** @param model какой моделью проверяли — чтобы сравнивать результаты между собой */
    public record ReviewResult(List<Finding> findings, boolean aiConfigured, String error, String model) {}

    /** Модели, между которыми можно переключаться в интерфейсе. */
    public record ReviewModels(List<String> models, String defaultModel) {}

    public boolean isAvailable() {
        return aiClient.hasReviewModel();
    }

    /**
     * Список моделей из настроек. Пустой, если задана только одна: тогда выбирать
     * нечего и в интерфейсе переключатель не нужен.
     */
    public ReviewModels models() {
        List<String> models = new ArrayList<>();
        String configured = props.ai().reviewModels();
        if (configured != null && !configured.isBlank()) {
            for (String m : configured.split(",")) {
                String name = m.strip();
                if (!name.isEmpty() && !models.contains(name)) models.add(name);
            }
        }
        String fallback = aiClient.defaultReviewModel();
        if (models.isEmpty() && fallback != null && !fallback.isBlank()) models.add(fallback);
        String byDefault = fallback != null && !fallback.isBlank() ? fallback
                : (models.isEmpty() ? null : models.get(0));
        return new ReviewModels(models, byDefault);
    }

    public ReviewResult review(Long estimateId) {
        return review(estimateId, null);
    }

    public ReviewResult review(Long estimateId, String requestedModel) {
        Estimate estimate = estimateService.get(estimateId);
        List<EstimateRow> rows = rowRepository.findByEstimateIdOrderByPosition(estimateId);
        ReviewModels available = models();
        String model = available.defaultModel();
        if (requestedModel != null && !requestedModel.isBlank()) {
            // принимаем только модель из настроек: произвольная строка из браузера
            // означала бы запрос к чему угодно за счёт владельца ключа
            String asked = requestedModel.strip();
            if (!available.models().contains(asked)) {
                return new ReviewResult(List.of(), isAvailable(),
                        "Модель «" + asked + "» не разрешена. Добавьте её в AI_REVIEW_MODELS.", model);
            }
            model = asked;
        }

        if (rows.isEmpty()) {
            return new ReviewResult(List.of(), isAvailable(), "В смете нет строк.", model);
        }
        if (!isAvailable()) {
            return new ReviewResult(List.of(), false,
                    "Модель проверки не настроена: задайте AI_REVIEW_MODEL (и при необходимости "
                            + "AI_REVIEW_BASE_URL, AI_REVIEW_API_KEY).", model);
        }

        String answer;
        try {
            answer = aiClient.completeReview(systemPrompt(), userPrompt(estimate, rows), model);
        } catch (Exception e) {
            log.warn("Проверка сметы {} моделью {} не удалась: {}", estimateId, model, e.getMessage());
            return new ReviewResult(List.of(), true,
                    "Модель не ответила: " + e.getClass().getSimpleName(), model);
        }
        if (answer == null || answer.isBlank()) {
            return new ReviewResult(List.of(), true, "Модель вернула пустой ответ.", model);
        }
        ReviewResult result = new ReviewResult(parse(answer, rows), true, null, model);
        save(estimateId, result);
        return result;
    }

    /**
     * Сохраняет проверку рядом со сметой, заменяя предыдущую. Неудачные прогоны
     * (модель не настроена, не ответила) не сохраняем: иначе они затирали бы
     * нормальный результат, полученный до них.
     */
    private void save(Long estimateId, ReviewResult result) {
        try {
            EstimateReview review = reviewRepository.findByEstimateId(estimateId)
                    .orElseGet(EstimateReview::new);
            review.setEstimateId(estimateId);
            review.setModel(result.model());
            review.setFindings(objectMapper.writeValueAsString(result.findings()));
            review.setCreatedAt(java.time.Instant.now());
            reviewRepository.save(review);
        } catch (Exception e) {
            log.warn("Не удалось сохранить проверку сметы {}: {}", estimateId, e.getMessage());
        }
    }

    /** Последняя сохранённая проверка сметы или null, если её ещё не делали. */
    public ReviewResult lastReview(Long estimateId) {
        return reviewRepository.findByEstimateId(estimateId).map(review -> {
            try {
                List<Finding> findings = objectMapper.readValue(review.getFindings(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Finding.class));
                return new ReviewResult(findings, isAvailable(), null, review.getModel());
            } catch (Exception e) {
                log.warn("Не удалось прочитать сохранённую проверку сметы {}: {}", estimateId, e.getMessage());
                return null;
            }
        }).orElse(null);
    }

    /** @param applied сколько правок применено, @param messages что именно сделано или почему нет */
    public record ApplyResult(int applied, int skipped, List<String> messages) {}

    /**
     * Применяет выбранные правки к смете. Меняются только те поля, что названы в
     * правке: цены пересчитываются из каталога по шифру, остальное остаётся как было.
     * В служебное обоснование строки пишется, по какому замечанию она изменена —
     * иначе потом не понять, откуда взялась правка.
     */
    @org.springframework.transaction.annotation.Transactional
    public ApplyResult apply(Long estimateId, List<Integer> indexes) {
        ReviewResult saved = lastReview(estimateId);
        if (saved == null) return new ApplyResult(0, 0, List.of("Сохранённой проверки нет."));

        List<Finding> findings = new ArrayList<>(saved.findings());
        List<String> messages = new ArrayList<>();
        int applied = 0, skipped = 0;
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            if (indexes != null && !indexes.contains(i)) continue;
            if (f.applied() || f.fix() == null) { skipped++; continue; }
            try {
                messages.add(applyFix(estimateId, f));
                findings.set(i, new Finding(f.position(), f.severity(), f.category(), f.title(),
                        f.detail(), f.impact(), f.fix(), true));
                applied++;
            } catch (Exception e) {
                skipped++;
                messages.add("Строка " + f.position() + ": не применено — " + e.getMessage());
            }
        }
        save(estimateId, new ReviewResult(findings, saved.aiConfigured(), null, saved.model()));
        return new ApplyResult(applied, skipped, messages);
    }

    private String applyFix(Long estimateId, Finding f) {
        EstimateRow row = rowRepository.findByEstimateIdOrderByPosition(estimateId).stream()
                .filter(r -> f.position().equals(r.getPosition()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("строки уже нет в смете"));
        Fix fix = f.fix();
        String note = "Изменено по замечанию проверки: " + f.title();

        return switch (fix.action()) {
            case "SET_RATE" -> {
                estimateService.updateRow(row.getId(), rowInput(fix.rateCode(), null, null, null, null, note));
                yield "Строка " + f.position() + ": расценка → " + fix.rateCode();
            }
            case "SET_PERIODICITY" -> {
                estimateService.updateRow(row.getId(),
                        rowInput(null, fix.periodicity(), fix.opsPerYear(), null, null, note));
                yield "Строка " + f.position() + ": периодичность → "
                        + (fix.periodicity() == null ? fix.opsPerYear() + " опер./год" : fix.periodicity());
            }
            case "SET_QTY" -> {
                estimateService.updateRow(row.getId(), rowInput(null, null, null, fix.qty(), null, note));
                yield "Строка " + f.position() + ": количество → " + fix.qty().stripTrailingZeros().toPlainString();
            }
            case "SET_OPERATION_NAME" -> {
                estimateService.updateRow(row.getId(),
                        rowInput(null, null, null, null, fix.operationName(), note));
                yield "Строка " + f.position() + ": мероприятие → " + fix.operationName();
            }
            case "ADD_ROW" -> {
                // пропущенная работа тому же оборудованию: описание берём из строки,
                // расценку и режим — из правки
                estimateService.addRow(estimateId, new EstimateService.RowInput(
                        row.getSection(), row.getEquipmentId(), row.getEquipmentName(),
                        row.getEquipmentType(), row.getManufacturer(),
                        fix.operationName() != null ? fix.operationName() : row.getOperationName(),
                        fix.rateCode(), null, fix.periodicity(), row.getJustification(),
                        fix.opsPerYear(), fix.qty() != null ? fix.qty() : row.getQty(),
                        null, null, null, null, null, null, null,
                        true, "MANUAL", null,
                        note + " (работа добавлена к строке " + f.position() + ")"));
                yield "Добавлена строка: " + fix.rateCode() + " к «" + row.getEquipmentName() + "»";
            }
            default -> throw new IllegalStateException("неизвестное действие " + fix.action());
        };
    }

    /** Точечная правка строки: null-поля EstimateService не трогает. */
    private EstimateService.RowInput rowInput(String rateCode, String periodicity, BigDecimal opsPerYear,
                                              BigDecimal qty, String operationName, String note) {
        return new EstimateService.RowInput(null, null, null, null, null, operationName,
                rateCode, null, periodicity, null, opsPerYear, qty,
                null, null, null, null, null, null, null,
                null, null, null, note);
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
                "impact":"влияние на деньги словами или пусто",
                "fix":{"action":"ADD_ROW","rateCode":"22-2203-109-1/1","periodicity":"раз в год",
                "opsPerYear":1,"qty":null,"operationName":"Техническое обслуживание"}}]}
                position — номер строки сметы, null для замечания по смете в целом.

                Поле fix — конкретная правка, которую приложение применит само.
                Добавляй его ТОЛЬКО когда изменение однозначно и следует из эталона
                или сборника; если нужно решение инженера — fix не указывай вовсе.
                Допустимые action:
                  SET_RATE — заменить шифр расценки строки (rateCode обязателен);
                  SET_PERIODICITY — исправить периодичность (periodicity, opsPerYear);
                  SET_QTY — проставить количество (qty);
                  SET_OPERATION_NAME — привести наименование работы к расценке (operationName);
                  ADD_ROW — добавить пропущенную работу тому же оборудованию
                            (rateCode, periodicity, opsPerYear; количество берётся из строки).
                Шифры бери только из присланных данных — эталона или строк сметы.
                Не предлагай удаление строк: это решение инженера.

                Важность (severity) определяй по последствию, а не по тому, насколько
                замечание выглядит формальным:
                HIGH — деньги посчитаны неверно или работа не попадёт в расчёт. Сюда
                  ВСЕГДА относятся: пустое или нулевое количество, отсутствие расценки
                  или периодичности, расценка на другое изделие, пропущенная работа,
                  разные расценки за одну и ту же работу (разная цена за одинаковое),
                  подозрение на неверный измеритель (цена за 10 шт применена к 1 шт).
                MEDIUM — вероятная ошибка, но нужно решение инженера: спорный аналог,
                  оборудование не из этой системы, необоснованная периодичность.
                LOW — только формулировки и оформление, на сумму не влияет:
                  наименование мероприятия не совпадает с наименованием расценки,
                  разнобой в написании периодичности.

                Если замечаний нет, верни {"findings":[]}.
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
                        node.path("impact").asText("").strip(),
                        fix(node.path("fix"), position),
                        false));
            }
        } catch (Exception e) {
            log.warn("Не удалось разобрать ответ проверки сметы: {}", e.getMessage());
        }
        return findings;
    }

    /** Действия, которые приложение умеет применять само. */
    private static final Set<String> ACTIONS = Set.of(
            "SET_RATE", "SET_PERIODICITY", "SET_QTY", "SET_OPERATION_NAME", "ADD_ROW");

    /**
     * Правка из ответа модели. Возвращает null, если применить нечего: без строки
     * или без обязательного значения правка бессмысленна, а кнопка «Применить»,
     * которая ничего не делает, хуже её отсутствия.
     */
    private Fix fix(JsonNode node, Integer position) {
        if (node == null || node.isMissingNode() || node.isNull() || position == null) return null;
        String action = node.path("action").asText("").strip().toUpperCase();
        if (!ACTIONS.contains(action)) return null;

        String rateCode = text(node.path("rateCode"));
        String periodicity = text(node.path("periodicity"));
        BigDecimal opsPerYear = decimal(node.path("opsPerYear"));
        BigDecimal qty = decimal(node.path("qty"));
        String operationName = text(node.path("operationName"));

        boolean usable = switch (action) {
            case "SET_RATE", "ADD_ROW" -> rateCode != null;
            case "SET_PERIODICITY" -> periodicity != null || opsPerYear != null;
            case "SET_QTY" -> qty != null && qty.signum() > 0;
            case "SET_OPERATION_NAME" -> operationName != null;
            default -> false;
        };
        return usable ? new Fix(action, rateCode, periodicity, opsPerYear, qty, operationName) : null;
    }

    private String text(JsonNode node) {
        String value = node == null || node.isNull() ? "" : node.asText("").strip();
        return value.isEmpty() ? null : value;
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText().strip());
        } catch (NumberFormatException e) {
            return null;
        }
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
