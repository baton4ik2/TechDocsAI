package ru.techdocs.pkm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import ru.techdocs.common.Periodicity;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Парсер регламента ПКМ. Поддерживает два формата:
 * <ul>
 *   <li><b>JSON</b> (основной) — структурированный, надёжный: {@code {"система", "регламент":[
 *       {"номер","категория_работ","тип_инцидента","чек_лист_действий":[...],"периодичность"}]}}.
 *       Именно его удобно готовить ИИ.</li>
 *   <li><b>DOCX</b> — таблица «№ | Категория | Вид и состав работ | Периодичность»
 *       (менее надёжно: объединённые ячейки, разные шаблоны).</li>
 * </ul>
 */
@Service
public class PkmParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Результат разбора: операции и (для JSON) тип системы из самого файла. */
    public record ParseResult(String systemType, List<PkmOperation> operations) {}

    public ParseResult parse(String filename, InputStream input) throws Exception {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".json")) {
            return parseJson(input);
        }
        return new ParseResult(null, parseDocx(input));
    }

    // ---------------------------------------------------------------- JSON

    private ParseResult parseJson(InputStream input) throws Exception {
        JsonNode root = objectMapper.readTree(input);
        JsonNode array = root.has("регламент") ? root.get("регламент")
                : root.has("операции") ? root.get("операции")
                : root.isArray() ? root : null;

        List<PkmOperation> ops = new ArrayList<>();
        if (array != null && array.isArray()) {
            int pos = 1;
            for (JsonNode item : array) {
                String rawName = firstText(item, "тип_инцидента", "операция", "наименование", "вид_работ");
                if (rawName == null || rawName.isBlank()) continue;

                PkmOperation op = new PkmOperation();
                op.setPosition(intVal(item, pos, "номер", "позиция", "n"));
                op.setCategory(firstText(item, "категория_работ", "категория"));
                op.setOperationName(operationName(rawName));
                op.setWorkComposition(jsonComposition(item, rawName));
                String periodicity = firstText(item, "периодичность", "период");
                op.setPeriodicity(blankToNull(periodicity));
                op.setPeriodicityPerYear(Periodicity.perYear(periodicity));
                ops.add(op);
                pos++;
            }
        }
        String systemType = firstText(root, "система", "тип_системы", "system");
        return new ParseResult(blankToNull(systemType), ops);
    }

    /** Состав работ: из массива «чек_лист_действий», иначе — из части после «:» в названии. */
    private String jsonComposition(JsonNode item, String rawName) {
        JsonNode checklist = item.has("чек_лист_действий") ? item.get("чек_лист_действий")
                : item.get("состав_работ");
        if (checklist != null && checklist.isArray() && checklist.size() > 0) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (JsonNode step : checklist) {
                String s = step.asText("").strip();
                if (!s.isBlank()) sb.append(i++).append(". ").append(s).append('\n');
            }
            String comp = sb.toString().strip();
            if (!comp.isBlank()) return comp;
        }
        // fallback: часть названия после двоеточия
        int colon = rawName.indexOf(':');
        if (colon >= 0 && colon + 1 < rawName.length()) {
            String comp = rawName.substring(colon + 1).strip()
                    .replaceAll("\\s+(\\d{1,2})[.)]\\s+", "\n$1. ").strip();
            return comp.isBlank() ? null : comp;
        }
        return null;
    }

    private String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText().strip();
        }
        return null;
    }

    private Integer intVal(JsonNode node, int fallback, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isInt()) return v.asInt();
            if (v != null && v.isTextual()) {
                var m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(v.asText());
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        }
        return fallback;
    }

    // ---------------------------------------------------------------- DOCX

    private List<PkmOperation> parseDocx(InputStream docx) throws Exception {
        List<PkmOperation> operations = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(docx)) {
            // В части регламентов шапка вынесена в ОТДЕЛЬНУЮ таблицу («№ | Категория |
            // Вид и состав работ | Периодичность»), а строки работ идут следующей таблицей
            // (её первая строка — только номера колонок). Поэтому найденную разметку
            // колонок продолжаем применять к последующим таблицам без своей шапки.
            int lastPeriodicityCol = -1;
            for (XWPFTable table : doc.getTables()) {
                int periodicityCol = findPeriodicityColumn(table);
                if (periodicityCol >= 0) {
                    lastPeriodicityCol = periodicityCol;
                } else if (lastPeriodicityCol < 0) {
                    continue;                       // шапки ещё не было — не та таблица
                }
                parseTable(table, periodicityCol >= 0 ? periodicityCol : lastPeriodicityCol, operations);
            }
        }
        int pos = 1;
        for (PkmOperation op : operations) {
            if (op.getPosition() == null) op.setPosition(pos);
            pos++;
        }
        return operations;
    }

    /** Ищем столбец «Периодичность» по строке-заголовку. */
    private int findPeriodicityColumn(XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<XWPFTableCell> cells = row.getTableCells();
            for (int c = 0; c < cells.size(); c++) {
                String text = cellText(cells.get(c)).toLowerCase();
                if (text.contains("периодичн")) return c;
            }
        }
        return -1;
    }

    private void parseTable(XWPFTable table, int periodicityCol, List<PkmOperation> out) {
        for (XWPFTableRow row : table.getRows()) {
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells.size() <= periodicityCol) continue;

            String worksCell = cellText(cells.get(Math.max(0, periodicityCol - 1)));
            String periodicity = cellText(cells.get(periodicityCol));
            if (isHeaderOrEmpty(worksCell, periodicity)) continue;

            String category = cells.size() >= 3 ? cellText(cells.get(1)) : null;
            Integer position = parsePosition(cellText(cells.get(0)));

            PkmOperation op = new PkmOperation();
            op.setPosition(position);
            op.setCategory(blankToNull(category));
            op.setOperationName(operationName(worksCell));
            op.setWorkComposition(docxComposition(worksCell));
            op.setPeriodicity(blankToNull(periodicity));
            op.setPeriodicityPerYear(Periodicity.perYear(periodicity));
            out.add(op);
        }
    }

    /** Наименование операции — текст до первого двоеточия («ТО …: 1. …» → «ТО …»). */
    private String operationName(String works) {
        int colon = works.indexOf(':');
        String name = colon > 0 ? works.substring(0, colon) : works;
        return name.strip().replaceAll("\\s{2,}", " ");
    }

    /** Состав работ (DOCX) — часть после двоеточия, пункты с новой строки. */
    private String docxComposition(String works) {
        int colon = works.indexOf(':');
        if (colon < 0 || colon + 1 >= works.length()) return null;
        String comp = works.substring(colon + 1).strip()
                .replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll("\\s+(\\d{1,2})[.)]\\s+", "\n$1. ")
                .strip();
        return comp.isBlank() ? null : comp;
    }

    private boolean isHeaderOrEmpty(String works, String periodicity) {
        if (works.isBlank()) return true;
        String w = works.strip().toLowerCase();
        if (w.contains("вид и состав") || w.equals("3")) return true;
        return works.strip().matches("\\d{1,2}") && periodicity.strip().matches("\\d{1,2}");
    }

    private Integer parsePosition(String s) {
        var m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(s == null ? "" : s);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private String cellText(XWPFTableCell cell) {
        if (cell == null) return "";
        return String.join(" ", cell.getParagraphs().stream()
                .map(p -> p.getText() == null ? "" : p.getText())
                .toList()).strip();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
