package ru.techdocs.pkm;

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
 * Парсер регламента ПКМ (docx): таблица «№ | Категория | Вид и состав работ |
 * Периодичность» → структурированные операции с нормализованной периодичностью.
 */
@Service
public class PkmParser {

    public List<PkmOperation> parse(InputStream docx) throws Exception {
        List<PkmOperation> operations = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(docx)) {
            for (XWPFTable table : doc.getTables()) {
                int periodicityCol = findPeriodicityColumn(table);
                if (periodicityCol < 0) continue; // не та таблица
                parseTable(table, periodicityCol, operations);
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

            String worksCell = cellText(cells.get(worksColumn(cells.size(), periodicityCol)));
            String periodicity = cellText(cells.get(periodicityCol));
            if (isHeaderOrEmpty(worksCell, periodicity)) continue;

            String category = cells.size() >= 3 ? cellText(cells.get(1)) : null;
            Integer position = parsePosition(cellText(cells.get(0)));

            PkmOperation op = new PkmOperation();
            op.setPosition(position);
            op.setCategory(blankToNull(category));
            op.setOperationName(operationName(worksCell));
            op.setWorkComposition(composition(worksCell));
            op.setPeriodicity(blankToNull(periodicity));
            op.setPeriodicityPerYear(Periodicity.perYear(periodicity));
            out.add(op);
        }
    }

    // столбец «Вид и состав работ» — предпоследний перед периодичностью (обычно индекс 2)
    private int worksColumn(int total, int periodicityCol) {
        return Math.max(0, periodicityCol - 1);
    }

    /** Наименование операции — текст до первого двоеточия («ТО …: 1. …» → «ТО …»). */
    private String operationName(String works) {
        int colon = works.indexOf(':');
        String name = colon > 0 ? works.substring(0, colon) : works;
        return name.strip().replaceAll("\\s{2,}", " ");
    }

    /** Состав работ — часть после двоеточия, пункты с новой строки. */
    private String composition(String works) {
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
        // строки-заголовки: «Вид и состав работ», «3» (нумерация колонок)
        if (w.contains("вид и состав") || w.equals("3")) return true;
        return works.strip().matches("\\d{1,2}") && periodicity.strip().matches("\\d{1,2}");
    }

    private Integer parsePosition(String s) {
        var m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(s == null ? "" : s);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private String cellText(XWPFTableCell cell) {
        if (cell == null) return "";
        // объединяем абзацы ячейки через пробел
        return String.join(" ", cell.getParagraphs().stream()
                .map(p -> p.getText() == null ? "" : p.getText())
                .toList()).strip();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
