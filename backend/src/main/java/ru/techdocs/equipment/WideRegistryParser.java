package ru.techdocs.equipment;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

/**
 * Парсер «широкого» реестра: инженерные системы идут горизонтальными блоками
 * (первая строка — название системы, вторая — заголовки колонок блока,
 * дальше — данные). Внутри блока могут быть подразделы (например, «СОУЭ»
 * внутри «Пожарной сигнализации») — строка, где заполнено только наименование.
 */
@Service
@Slf4j
public class WideRegistryParser {

    public record WideItem(String systemName, String manufacturer, String name,
                           String model, BigDecimal quantity, String unit) {}

    /** Прочерки и пустые пометки — не оборудование и не подраздел. */
    private static final Set<String> NOTE_ROWS = Set.of("-", "—", "–");

    /**
     * Пометка о виде документа, из которого взято оборудование и его количество
     * («По ИТД», «по РД», «по ПД и ИТД», «нет ИТД»). Это не подраздел реестра:
     * без этой проверки такая строка становилась бы «инженерной системой»,
     * и всё оборудование под ней уезжало в несуществующую систему.
     */
    private static final java.util.regex.Pattern DOCUMENT_NOTE = java.util.regex.Pattern.compile(
            "^(?:по|нет|согласно)?\\s*(?:итд|рд|пд|ид|пир|проект\\p{L}*)"
                    + "(?:\\s*(?:и|,|/)\\s*(?:итд|рд|пд|ид|пир|проект\\p{L}*))*$");

    private static boolean isDocumentNote(String lower) {
        return NOTE_ROWS.contains(lower) || DOCUMENT_NOTE.matcher(lower).matches();
    }

    /**
     * Подзаголовки, которые НЕ выделяются в отдельную инженерную систему: оборудование
     * под ними обслуживается в составе системы блока и попадает в ту же смету
     * (АГПТ/АУПТ — автоматическое пожаротушение внутри блока пожарной сигнализации).
     */
    private static final Set<String> SAME_SYSTEM_SUBHEADERS = Set.of(
            "агпт", "аупт", "апт", "пожаротушение", "газовое пожаротушение",
            "автоматическое газовое пожаротушение");

    private record Block(String systemName, int manufacturerCol, int nameCol,
                         int modelCol, int qtyCol, int unitCol) {}

    /** Возвращает пустой список, если файл не похож на широкий реестр. */
    public List<WideItem> parse(String label, InputStream input) {
        List<WideItem> items = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                parseSheet(workbook.getSheetAt(s), formatter, items);
            }
        } catch (Exception e) {
            log.warn("Не удалось разобрать широкий реестр {}: {}", label, e.getMessage());
        }
        return items;
    }

    private void parseSheet(Sheet sheet, DataFormatter formatter, List<WideItem> items) {
        Row systemRow = sheet.getRow(sheet.getFirstRowNum());
        Row headerRow = sheet.getRow(sheet.getFirstRowNum() + 1);
        if (systemRow == null || headerRow == null) return;

        // названия систем в первой строке → границы блоков
        List<int[]> blockBounds = new ArrayList<>(); // [startCol, имяВindex]
        List<String> blockNames = new ArrayList<>();
        for (Cell cell : systemRow) {
            String value = formatter.formatCellValue(cell).strip();
            if (!value.isBlank()) {
                blockBounds.add(new int[]{cell.getColumnIndex()});
                blockNames.add(value);
            }
        }
        if (blockNames.size() < 2) return; // не широкий формат — пусть работает плоский парсер

        List<Block> blocks = new ArrayList<>();
        for (int b = 0; b < blockBounds.size(); b++) {
            int start = blockBounds.get(b)[0];
            int end = (b + 1 < blockBounds.size()) ? blockBounds.get(b + 1)[0] - 1
                    : headerRow.getLastCellNum();
            Block block = mapHeaders(blockNames.get(b), headerRow, formatter, start, end);
            if (block != null) blocks.add(block);
        }
        if (blocks.size() < 2) return;

        // подраздел действует для конкретного блока до следующего подзаголовка
        Map<Integer, String> subsystemOf = new HashMap<>();

        for (int r = sheet.getFirstRowNum() + 2; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int b = 0; b < blocks.size(); b++) {
                Block block = blocks.get(b);
                String name = cell(row, block.nameCol(), formatter);
                String manufacturer = cell(row, block.manufacturerCol(), formatter);
                String model = cell(row, block.modelCol(), formatter);
                String qtyRaw = cell(row, block.qtyCol(), formatter);

                if (name.isBlank()) continue;
                String lower = name.toLowerCase(Locale.ROOT).strip();
                if (isDocumentNote(lower)) continue;

                boolean onlyName = manufacturer.isBlank() && model.isBlank() && qtyRaw.isBlank();
                if (onlyName && name.length() <= 60) {
                    // подзаголовок внутри блока: «СОУЭ», «АОВ», «АДУ», «АГПТ».
                    // Пожаротушение остаётся в системе блока — считаем в той же смете;
                    // остальные подзаголовки выделяются в свою систему.
                    String sub = name.strip();
                    if (SAME_SYSTEM_SUBHEADERS.contains(sub.toLowerCase(Locale.ROOT))) {
                        subsystemOf.remove(b);
                    } else {
                        subsystemOf.put(b, sub);
                    }
                    continue;
                }

                BigDecimal quantity = parseQuantity(qtyRaw);
                String unit = block.unitCol() >= 0 ? cell(row, block.unitCol(), formatter) : "";
                String system = subsystemOf.getOrDefault(b, block.systemName());
                items.add(new WideItem(
                        system,
                        manufacturer.isBlank() || manufacturer.equals("—") ? null : manufacturer,
                        name.strip(),
                        model.isBlank() || model.equals("—") ? null : model,
                        quantity,
                        unit.isBlank() ? "шт." : unit));
            }
        }
    }

    private Block mapHeaders(String systemName, Row headerRow, DataFormatter formatter,
                             int startCol, int endCol) {
        int manufacturer = -1, name = -1, model = -1, qty = -1, unit = -1;
        for (int c = startCol; c <= endCol && c >= 0; c++) {
            String value = cell(headerRow, c, formatter).toLowerCase(Locale.ROOT);
            if (value.isBlank()) continue;
            if (manufacturer < 0 && (value.contains("производител") || value.contains("изготовител"))) manufacturer = c;
            else if (name < 0 && (value.contains("наименование") || value.contains("название"))) name = c;
            else if (model < 0 && (value.contains("модель") || value.contains("марка") || value.equals("тип"))) model = c;
            else if (qty < 0 && (value.contains("кол-во") || value.contains("количество") || value.contains("кол."))) qty = c;
            else if (unit < 0 && (value.contains("ед. изм") || value.contains("ед.изм"))) unit = c;
        }
        if (name < 0) return null;
        return new Block(systemName.strip(), manufacturer, name, model, qty, unit);
    }

    private String cell(Row row, int col, DataFormatter formatter) {
        if (col < 0) return "";
        Cell cell = row.getCell(col);
        return cell == null ? "" : formatter.formatCellValue(cell).strip();
    }

    private BigDecimal parseQuantity(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.replace(" ", "").replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
