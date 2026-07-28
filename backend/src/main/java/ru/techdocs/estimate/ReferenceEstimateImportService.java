package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.Periodicity;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Импорт эталонной сметы (XLSX) в память решений. Читает строки по заголовкам
 * колонок (наименование/тип/производитель оборудования, шифр расценки,
 * мероприятие, периодичность) и сохраняет решения (source=REFERENCE).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReferenceEstimateImportService {

    private final EstimateDecisionService decisionService;
    private final DataFormatter formatter = new DataFormatter();

    public record ImportResult(int imported, int rows) {}

    public ImportResult importXlsx(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw new BadRequestException("Эталон загружается в формате XLSX.");
        }
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                Map<String, Integer> cols = findHeader(sheet);
                if (cols == null) continue;                 // на листе нет таблицы расценок
                return importSheet(sheet, cols);
            }
            throw new BadRequestException("Не найден лист с колонками «Шифр расценки» и «оборудование».");
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ошибка импорта эталона: {}", e.getMessage(), e);
            throw new BadRequestException("Не удалось прочитать файл эталона: " + e.getClass().getSimpleName());
        }
    }

    /** Ищет строку заголовков по ключевым словам, возвращает карту роль→колонка. */
    private Map<String, Integer> findHeader(Sheet sheet) {
        for (Row row : sheet) {
            Map<String, Integer> cols = new HashMap<>();
            for (Cell cell : row) {
                String t = formatter.formatCellValue(cell).toLowerCase().replace('ё', 'е');
                int c = cell.getColumnIndex();
                if (t.contains("шифр")) cols.put("code", c);
                else if (t.contains("наименование оборуд")) cols.put("name", c);
                else if (t.contains("тип оборуд")) cols.put("type", c);
                else if (t.contains("производител")) cols.put("manufacturer", c);
                else if (t.contains("наименование меропр")) cols.put("operation", c);
                // «периодичность операции», а не «обоснование периодичности» (там длинный текст)
                else if (t.contains("периодичность") && !t.contains("обоснован")) cols.put("periodicity", c);
                // фактическое число операций в год (осмотр «раз в месяц» может быть 10, а не 12,
                // т.к. ТО поглощает часть осмотров) — приоритетнее, чем разбор текста периодичности
                else if (t.contains("количество операций")) cols.put("opsPerYear", c);
            }
            if (cols.containsKey("code") && cols.containsKey("name")) {
                return cols;
            }
        }
        return null;
    }

    private ImportResult importSheet(Sheet sheet, Map<String, Integer> cols) {
        int headerRow = -1;
        // повторно находим индекс строки заголовков, чтобы стартовать ниже неё
        for (Row row : sheet) {
            if (cell(row, cols.get("code")).toLowerCase().contains("шифр")) {
                headerRow = row.getRowNum();
                break;
            }
        }
        // Система листа: не во всех эталонах есть строки-разделы («РАЗДЕЛ А. …»).
        // Тогда берём её из названия листа («АПС СН-2012 расчёт»), иначе из заголовка
        // над таблицей — чтобы решения не сохранялись «без системы».
        String sheetSystem = ru.techdocs.common.SystemNormalizer.recognized(sheet.getSheetName());
        if (sheetSystem == null) sheetSystem = titleSystem(sheet, headerRow);
        java.util.List<EstimateDecisionService.DecisionData> data = new java.util.ArrayList<>();
        String currentSystem = null;   // текущая инженерная система блока (из строки-раздела)
        for (Row row : sheet) {
            if (row.getRowNum() <= headerRow) continue;
            String code = cell(row, cols.get("code"));
            String name = cell(row, cols.get("name"));
            if (name.isBlank()) continue;                       // пустые строки
            if (code.isBlank() || code.equals("-") || code.equals("—")) {
                // строка-раздел («РАЗДЕЛ А. СИСТЕМА КОНТРОЛЯ…»): имя есть, шифр пуст.
                // Распознанную систему запоминаем как текущую; служебные («ИТОГО») игнорируем.
                String recognized = ru.techdocs.common.SystemNormalizer.recognized(name);
                if (recognized != null) currentSystem = recognized;
                continue;                                       // сама строка расценки не несёт
            }

            String periodicity = cell(row, cols.get("periodicity"));
            BigDecimal perYear = numeric(cell(row, cols.get("opsPerYear")));  // фактическая колонка
            if (perYear == null) perYear = Periodicity.perYear(periodicity);   // иначе — из текста

            // система строки: раздел → распознанная по мероприятию (в смешанном листе
            // «АПС и СОУЭ» строки оповещателей уходят в СОУЭ) → система листа
            String operation = cell(row, cols.get("operation"));
            String system = currentSystem;
            if (system == null) system = ru.techdocs.common.SystemNormalizer.recognized(operation);
            if (system == null) system = sheetSystem;

            data.add(new EstimateDecisionService.DecisionData(
                    name, cell(row, cols.get("type")), cell(row, cols.get("manufacturer")), system,
                    operation, code, null,
                    blank(periodicity), perYear, null));
        }
        int imported = decisionService.saveAll(data, EstimateRateDecision.SOURCE_REFERENCE).size();
        return new ImportResult(imported, data.size());
    }

    private String cell(Row row, Integer col) {
        if (col == null || row == null) return "";
        Cell c = row.getCell(col);
        return c == null ? "" : formatter.formatCellValue(c).strip();
    }

    /** Система из заголовка над таблицей («АПС и СОУЭ» в строке над шапкой). */
    private String titleSystem(Sheet sheet, int headerRow) {
        for (int r = sheet.getFirstRowNum(); r < headerRow && r >= 0; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell c : row) {
                String recognized = ru.techdocs.common.SystemNormalizer.recognized(
                        formatter.formatCellValue(c).strip());
                if (recognized != null) return recognized;
            }
        }
        return null;
    }

    /** Число из ячейки (пробелы/запятая), иначе null. */
    private BigDecimal numeric(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.replaceAll("[\\s\\u00A0]", "").replace(',', '.');
        try {
            BigDecimal n = new BigDecimal(v);
            return n.signum() <= 0 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blank(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.strip();
        return v.length() > 200 ? v.substring(0, 200) : v;   // periodicity — varchar(200)
    }
}
