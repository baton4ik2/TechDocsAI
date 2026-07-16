package ru.techdocs.equipment;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import ru.techdocs.document.Document;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Эвристическое извлечение оборудования из Excel-спецификаций.
 * Ищет строку заголовков по ключевым словам и извлекает строки таблицы.
 */
@Service
@Slf4j
public class XlsxEquipmentExtractor {

    public record ExtractedItem(String manufacturer, String name, String model,
                                BigDecimal quantity, String unit, int sheetNumber, String sourceRow) {}

    private static final List<String> NAME_HEADERS = List.of("наименование", "название", "оборудование");
    private static final List<String> MODEL_HEADERS = List.of("модель", "тип, марка", "марка", "тип", "артикул", "обозначение");
    private static final List<String> QTY_HEADERS = List.of("кол-во", "количество", "кол.", "qty");
    private static final List<String> MANUFACTURER_HEADERS = List.of("производитель", "изготовитель", "завод");
    private static final List<String> UNIT_HEADERS = List.of("ед. изм", "ед.изм", "единица");

    public List<ExtractedItem> extract(Document document, InputStream input) {
        List<ExtractedItem> items = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                extractFromSheet(sheet, sheetIndex + 1, formatter, items);
            }
        } catch (Exception e) {
            log.warn("Не удалось извлечь оборудование из {}: {}", document.getOriginalFilename(), e.getMessage());
        }
        return items;
    }

    private void extractFromSheet(Sheet sheet, int sheetNumber, DataFormatter formatter,
                                  List<ExtractedItem> items) {
        int nameCol = -1, modelCol = -1, qtyCol = -1, manufacturerCol = -1, unitCol = -1, headerRow = -1;

        for (Row row : sheet) {
            if (row.getRowNum() > 30) break; // заголовок ищем в первых строках
            for (Cell cell : row) {
                String value = formatter.formatCellValue(cell).strip().toLowerCase(Locale.ROOT);
                if (value.isBlank()) continue;
                int col = cell.getColumnIndex();
                if (nameCol < 0 && matches(value, NAME_HEADERS)) { nameCol = col; headerRow = row.getRowNum(); }
                else if (modelCol < 0 && matches(value, MODEL_HEADERS)) { modelCol = col; headerRow = row.getRowNum(); }
                else if (qtyCol < 0 && matches(value, QTY_HEADERS)) { qtyCol = col; headerRow = row.getRowNum(); }
                else if (manufacturerCol < 0 && matches(value, MANUFACTURER_HEADERS)) { manufacturerCol = col; }
                else if (unitCol < 0 && matches(value, UNIT_HEADERS)) { unitCol = col; }
            }
            if (nameCol >= 0 && qtyCol >= 0) break;
        }

        if (nameCol < 0 || qtyCol < 0 || headerRow < 0) {
            return; // на листе нет распознаваемой таблицы спецификации
        }

        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String name = cellValue(row, nameCol, formatter);
            String qtyRaw = cellValue(row, qtyCol, formatter);
            BigDecimal quantity = parseQuantity(qtyRaw);
            if (name.isBlank() || quantity == null || quantity.signum() <= 0) continue;

            String model = modelCol >= 0 ? cellValue(row, modelCol, formatter) : "";
            String manufacturer = manufacturerCol >= 0 ? cellValue(row, manufacturerCol, formatter) : "";
            String unit = unitCol >= 0 ? cellValue(row, unitCol, formatter) : "шт.";
            if (unit.isBlank()) unit = "шт.";

            StringBuilder source = new StringBuilder();
            for (Cell cell : row) {
                String v = formatter.formatCellValue(cell).strip();
                if (!v.isBlank()) source.append(v).append(" | ");
            }
            items.add(new ExtractedItem(manufacturer, name, model, quantity, unit,
                    sheetNumber, source.toString()));
        }
    }

    private boolean matches(String value, List<String> keywords) {
        return keywords.stream().anyMatch(value::contains);
    }

    private String cellValue(Row row, int col, DataFormatter formatter) {
        Cell cell = row.getCell(col);
        return cell == null ? "" : formatter.formatCellValue(cell).strip();
    }

    private BigDecimal parseQuantity(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.replace(" ", "").replace(" ", "").replace(',', '.');
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
