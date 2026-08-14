package ru.techdocs.midio;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.config.AppProperties;
import ru.techdocs.midio.MidioEquipmentMatcher.ExternalEquipment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Клиент Midio поверх их execute-API: {@code POST /api/execute/{Domain.Method}}
 * с общим конвертом тела и Bearer-токеном из {@code Auth.Signin}.
 * <p>
 * Только чтение. Синхронизация переносит регламенты к нам и ничего не меняет на
 * их стороне — создание и удаление планов остаётся ручной операцией в Midio.
 */
@Service
@Slf4j
public class MidioHttpClient implements MidioClient {

    private static final String APP_VERSION = "1.0.0";

    private final AppProperties.Midio props;
    private final RestClient http;
    private volatile String token;
    /** id карточки → модель: нужна работам, чтобы найти своё изделие в плане. */
    private volatile Map<String, String> equipmentModels;

    public MidioHttpClient(AppProperties props) {
        this.props = props.midio();
        this.http = isConfigured() ? build(this.props.baseUrl()) : null;
    }

    private static RestClient build(String baseUrl) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(factory)
                .build();
    }

    @Override
    public boolean isConfigured() {
        if (props == null) return false;
        boolean hasBase = props.baseUrl() != null && !props.baseUrl().isBlank();
        boolean hasCredentials = (props.token() != null && !props.token().isBlank())
                || (props.login() != null && !props.login().isBlank()
                    && props.password() != null && !props.password().isBlank());
        return hasBase && hasCredentials;
    }

    @Override
    public List<ExternalEquipment> equipment() {
        JsonNode response = execute("Equipment.GetEquipmentList", Map.of());
        List<ExternalEquipment> result = new ArrayList<>();
        Map<String, String> models = new HashMap<>();
        for (JsonNode node : array(response, "equipmentList", "equipment", "items")) {
            String id = text(node, "equipmentId", "id");
            if (id == null) continue;
            var split = MidioMapping.splitModel(text(node, "model"));
            if (split.model() != null) models.put(id, split.model());
            result.add(new ExternalEquipment(id, split.name(), split.model(),
                    manufacturer(node), MidioMapping.systemName(text(node, "engineeringSystemType"))));
        }
        equipmentModels = models;
        log.info("Midio: получено {} карточек оборудования", result.size());
        return result;
    }

    private Map<String, String> models() {
        Map<String, String> cached = equipmentModels;
        if (cached == null) {
            equipment();
            cached = equipmentModels;
        }
        return cached == null ? Map.of() : cached;
    }

    @Override
    public List<ExternalWork> works() {
        JsonNode plans = execute("MaintenancePlans.GetList", Map.of());
        List<ExternalWork> result = new ArrayList<>();
        int planCount = 0;
        int noEquipmentLink = 0;
        int noRecurrence = 0;
        int zoneSkipped = 0;
        JsonNode samplePlan = null;
        JsonNode sampleNoRecurrence = null;
        for (JsonNode plan : array(plans, "maintenancePlans", "plans", "items")) {
            String planId = text(plan, "id", "maintenancePlanId", "planId");
            if (planId == null) continue;
            planCount++;
            List<String> planEquipmentIds = equipmentRefs(plan);
            String planName = text(plan, "name", "title");
            if (samplePlan == null && planEquipmentIds.isEmpty()) samplePlan = plan;
            JsonNode incidents = execute("PlannedIncidents.GetByMaintenancePlan",
                    Map.of("maintenancePlanId", asNumberOrText(planId)));
            for (JsonNode item : array(incidents, "items", "plannedIncidents", "incidents")) {
                String title = text(item, "title", "name");
                if (title == null || title.isBlank()) continue;
                // работы про пожарную ЗОНУ — сущность диспетчеризации, не изделие;
                // в регламент оборудования они не переносятся
                if (MidioMapping.mentionsZone(title)) {
                    zoneSkipped++;
                    continue;
                }
                // у работы может стоять собственная ссылка на оборудование; без неё
                // работа относится ко всему оборудованию плана — план в Midio накрывает
                // несколько изделий сразу (equipmentIds: [372, 144, …])
                List<String> targets = equipmentRefs(item);
                // своя ссылка на изделие или план на одно изделие = «своя» работа;
                // зонный план (несколько изделий) — работа запасная
                boolean dedicated = !targets.isEmpty() || planEquipmentIds.size() == 1;
                // работа зонного плана сама изделия не знает — находим его по модели
                // в названии; не нашли или работа общая — идёт всем изделиям плана
                if (targets.isEmpty()) targets = MidioMapping.workTargets(title, planEquipmentIds, models());
                if (targets.isEmpty()) {
                    noEquipmentLink++;
                    targets = java.util.Collections.singletonList(null);
                }
                var recurrence = recurrence(item);
                if (recurrence.perYear() == null) {
                    noRecurrence++;
                    if (sampleNoRecurrence == null) sampleNoRecurrence = item;
                }
                for (String equipmentId : targets) {
                    result.add(new ExternalWork(
                            text(item, "plannedIncidentId", "id"), equipmentId, title,
                            MidioMapping.workType(title), recurrence.text(), recurrence.perYear(),
                            composition(item),
                            MidioMapping.mandatory(text(item, "category", "incidentCategory", "type")),
                            dedicated, planName));
                }
            }
        }
        log.info("Midio: получено {} регламентных работ из {} планов обслуживания"
                        + (noEquipmentLink > 0 ? ", из них БЕЗ привязки к оборудованию: " + noEquipmentLink : "")
                        + (noRecurrence > 0 ? ", без периодичности: " + noRecurrence : "")
                        + (zoneSkipped > 0 ? ", пропущено зонных: " + zoneSkipped : ""),
                result.size(), planCount);
        if (samplePlan != null && noEquipmentLink > 0) {
            // структура живого ответа — прямо в лог: без неё причину не назвать
            log.warn("Midio: пример плана без распознанной ссылки на оборудование: {}", samplePlan);
        }
        if (sampleNoRecurrence != null) {
            log.warn("Midio: пример работы без распознанной периодичности: {}", sampleNoRecurrence);
        }
        return result;
    }

    /**
     * Периодичность работы: interval + unit. По документации это плоские поля
     * recurrenceInterval / recurrenceIntervalUnit, но живые ответы бывают с
     * вложенным объектом recurrence — ищем и там, прежде чем сдаться.
     */
    private MidioMapping.Recurrence recurrence(JsonNode item) {
        Integer interval = integer(item, "recurrenceInterval");
        String unit = text(item, "recurrenceIntervalUnit", "recurrenceUnit");
        if (interval == null || unit == null) {
            for (String key : new String[]{"recurrenceConfig", "recurrence", "recurrenceRule", "schedule"}) {
                JsonNode nested = item.get(key);
                if (nested == null || !nested.isObject()) continue;
                if (interval == null) interval = integer(nested, "recurrenceInterval", "interval");
                if (unit == null) unit = text(nested, "recurrenceIntervalUnit", "intervalUnit", "unit");
            }
        }
        return MidioMapping.recurrence(interval, unit);
    }

    /**
     * Ссылки на карточки оборудования. В документации это одиночное поле
     * equipmentId, но живой ответ планов несёт массив equipmentIds — один план
     * накрывает несколько изделий сразу. Без ссылки работа не найдёт свою
     * запись реестра.
     */
    private List<String> equipmentRefs(JsonNode node) {
        String direct = text(node, "equipmentId", "equipmentCardId");
        if (direct != null) return List.of(direct);
        JsonNode nested = node.get("equipment");
        if (nested != null && nested.isObject()) {
            String fromNested = text(nested, "id", "equipmentId");
            if (fromNested != null) return List.of(fromNested);
        }
        JsonNode list = node.get("equipmentIds");
        if (list != null && list.isArray()) {
            List<String> ids = new ArrayList<>();
            for (JsonNode id : list) {
                if (!id.isNull() && !id.asText().isBlank()) ids.add(id.asText().strip());
            }
            return ids;
        }
        return List.of();
    }

    /** Состав работы: чеклист, иначе описание — то, что инженер увидит в строке сметы. */
    private String composition(JsonNode item) {
        JsonNode checklist = item.get("checklistItems");
        if (checklist != null && checklist.isArray() && !checklist.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (JsonNode c : checklist) {
                String line = c.isTextual() ? c.asText() : text(c, "title", "name", "text");
                if (line != null && !line.isBlank()) lines.add(line.strip());
            }
            if (!lines.isEmpty()) return String.join("; ", lines);
        }
        return text(item, "description");
    }

    /** Производитель приходит и строкой, и объектом — берём имя в обоих случаях. */
    private String manufacturer(JsonNode node) {
        JsonNode value = node.get("manufacturer");
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) return value.asText().strip();
        return text(value, "name", "title");
    }

    // ---------- транспорт ----------

    private JsonNode execute(String method, Map<String, Object> payload) {
        if (!isConfigured()) {
            throw new BadRequestException("Интеграция с Midio не настроена: укажите адрес и учётные данные.");
        }
        try {
            return call(method, payload, authToken());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() != 401) throw failure(method, e);
            // токен протух — один раз перелогиниваемся и повторяем
            token = null;
            try {
                return call(method, payload, authToken());
            } catch (Exception retry) {
                throw failure(method, retry);
            }
        } catch (Exception e) {
            throw failure(method, e);
        }
    }

    private JsonNode call(String method, Map<String, Object> payload, String bearer) {
        Map<String, Object> body = envelope(method, payload);
        var request = http.post().uri("/api/execute/" + method)
                .header("Content-Type", "application/json");
        if (bearer != null) request = request.header("Authorization", "Bearer " + bearer);
        JsonNode response = request.body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { /* разбираем ниже по телу */ })
                .body(JsonNode.class);
        return checked(method, response);
    }

    /**
     * Midio отвечает 200 и кладёт причину в тело («Wrong body format», «Unauthorized»).
     * Без этой проверки ошибка превратилась бы в пустой список и выглядела как
     * «в Midio ничего нет».
     */
    private JsonNode checked(String method, JsonNode response) {
        if (response == null) {
            throw new BadRequestException("Midio не ответил на запрос " + method + ".");
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            String message = error.isTextual() ? error.asText() : text(error, "message", "description");
            if (message == null) message = error.toString();
            if (message.toLowerCase().contains("unauthorized")) {
                throw new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, message);
            }
            throw new BadRequestException("Midio отклонил запрос " + method + ": " + message);
        }
        return response;
    }

    private Map<String, Object> envelope(String method, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appVersion", APP_VERSION);
        body.put("clientSyncId", UUID.randomUUID().toString());
        body.put("method", method);
        body.putAll(payload);
        return body;
    }

    private String authToken() {
        if (props.token() != null && !props.token().isBlank()) return props.token().strip();
        String current = token;
        if (current != null) return current;
        synchronized (this) {
            if (token != null) return token;
            Map<String, Object> body = envelope("Auth.Signin",
                    Map.of("login", props.login(), "password", props.password()));
            JsonNode response = checked("Auth.Signin", http.post().uri("/api/execute/Auth.Signin")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })
                    .body(JsonNode.class));
            String issued = token(response);
            if (issued == null) {
                throw new BadRequestException("Midio не выдал токен: проверьте логин и пароль.");
            }
            token = issued;
            return issued;
        }
    }

    private String token(JsonNode response) {
        JsonNode pair = response.get("tokenPair");
        if (pair != null) {
            String value = text(pair, "authToken", "accessToken", "token");
            if (value != null) return value;
        }
        return text(response, "authToken", "accessToken", "token");
    }

    private BadRequestException failure(String method, Exception e) {
        log.warn("Midio: запрос {} не удался: {}", method, e.getMessage());
        if (e instanceof BadRequestException bad) return bad;
        return new BadRequestException("Midio недоступен (" + method + "): " + e.getMessage());
    }

    // ---------- разбор ответа ----------

    /**
     * Массив из ответа: по ожидаемым именам поля, иначе первый массив в объекте.
     * Имена в ответах разнятся между методами, а привязываться к одному значит
     * ломаться на первом же расхождении.
     */
    static List<JsonNode> array(JsonNode response, String... keys) {
        if (response == null) return List.of();
        if (response.isArray()) return toList(response);
        for (String key : keys) {
            JsonNode node = response.get(key);
            if (node != null && node.isArray()) return toList(node);
        }
        Map<String, JsonNode> nested = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = response.fields(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue().isArray()) return toList(entry.getValue());
            if (entry.getValue().isObject()) nested.put(entry.getKey(), entry.getValue());
        }
        // ответ бывает завёрнут ещё на уровень («data»/«result») — заглядываем внутрь
        for (JsonNode node : nested.values()) {
            List<JsonNode> inner = array(node, keys);
            if (!inner.isEmpty()) return inner;
        }
        return List.of();
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    static String text(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText().strip();
        }
        return null;
    }

    private static Integer integer(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) continue;
            if (value.isNumber()) return value.asInt();
            String raw = value.asText().strip();
            if (raw.matches("\\d+")) return Integer.parseInt(raw);
        }
        return null;
    }

    /** Идентификаторы у Midio числовые — строкой их метод может не принять. */
    private static Object asNumberOrText(String id) {
        return id.matches("-?\\d+") ? Long.parseLong(id) : id;
    }
}
