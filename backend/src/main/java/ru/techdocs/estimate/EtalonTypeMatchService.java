package ru.techdocs.estimate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.techdocs.ai.AiClient;
import ru.techdocs.equipment.Equipment;

import java.util.List;

/**
 * Сопоставление оборудования объекта с ТИПОМ оборудования эталона через ИИ.
 * <p>
 * Нужно там, где названия синонимичны, но не совпадают ни одним словом: в эталоне
 * «Блок питания», на объекте «Источник вторичного электропитания резервированный
 * (для STR-1AP)». Токенами такое не сопоставить, а модель — легко.
 * <p>
 * ИИ выбирает только ТИП; расценки и периодичность после этого берутся из эталона
 * детерминированно (все операции типа: осмотр + ТО), без участия ИИ в цифрах.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EtalonTypeMatchService {

    private static final int TYPE_LIMIT = 60;   // сколько типов эталона показывать модели

    private final AiClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isAvailable() {
        return aiClient.hasMatchModel();
    }

    /**
     * Тип эталона, соответствующий оборудованию объекта, или null.
     * Модель отвечает индексом из показанного списка; всё вне списка отбрасывается.
     */
    public EstimateDecisionService.EtalonType match(Equipment equipment,
                                                    List<EstimateDecisionService.EtalonType> types) {
        if (types == null || types.isEmpty() || !isAvailable()) return null;
        List<EstimateDecisionService.EtalonType> shown =
                types.size() > TYPE_LIMIT ? types.subList(0, TYPE_LIMIT) : types;

        String answer = aiClient.completeMatch(systemPrompt(), userPrompt(equipment, shown));
        if (answer == null || answer.isBlank()) return null;
        try {
            int start = answer.indexOf('{');
            int end = answer.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            JsonNode node = objectMapper.readTree(answer.substring(start, end + 1));
            JsonNode idx = node.get("index");
            if (idx == null || idx.isNull()) return null;
            int i = idx.asInt(-1);
            if (i < 1 || i > shown.size()) return null;    // вне списка — отбрасываем
            return shown.get(i - 1);
        } catch (Exception e) {
            log.warn("Не удалось разобрать ответ ИИ-сопоставления типа: {}", e.getMessage());
            return null;
        }
    }

    private String systemPrompt() {
        return """
                Ты — инженер по обслуживанию инженерных систем зданий.
                Тебе дают оборудование объекта и пронумерованный список типов оборудования
                из эталонной сметы. Определи, какому типу эталона соответствует оборудование
                объекта — то есть это ТО ЖЕ оборудование по назначению, даже если названия
                разные (например, «Блок питания» = «Источник вторичного электропитания
                резервированный»; «ИБП» = «Источник бесперебойного питания»).
                Правила:
                - учитывай назначение, модель и производителя, а не только совпадение слов;
                - если подходящего типа нет — верни null;
                - не путай разные по назначению устройства (контроллер ≠ считыватель,
                  источник питания ≠ аккумулятор, замок ≠ кнопка выхода).
                Ответ — строго JSON {"index": N} с номером из списка либо {"index": null}.
                """;
    }

    private String userPrompt(Equipment eq, List<EstimateDecisionService.EtalonType> types) {
        StringBuilder sb = new StringBuilder("Оборудование объекта: ");
        sb.append(eq.getName() == null ? "" : eq.getName());
        if (eq.getModel() != null && !eq.getModel().isBlank()) sb.append(" | модель: ").append(eq.getModel());
        if (eq.getManufacturer() != null && !eq.getManufacturer().isBlank()) {
            sb.append(" | производитель: ").append(eq.getManufacturer());
        }
        sb.append("\n\nТипы оборудования эталона:\n");
        int i = 1;
        for (EstimateDecisionService.EtalonType t : types) {
            sb.append(i++).append(". ").append(t.name());
            if (t.model() != null && !t.model().isBlank()) sb.append(" | модель: ").append(t.model());
            sb.append('\n');
        }
        return sb.toString();
    }
}
