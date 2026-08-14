package ru.techdocs.estimate;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

/**
 * Экспорт сметы в XLSX в формате эталонного расчёта (43 колонки + лист коэффициентов).
 * Оформление повторяет эталон: строка-заголовок расчёта, «шапка» с переносом и цветовыми
 * группами (синяя — СН-2012, зелёная — уровень РТ, янтарная — расчётные колонки),
 * тонкие рамки, денежный формат, ширины колонок и закрепление шапки.
 */
@Service
public class EstimateXlsxExporter {

    private static final String[] HEADERS = {
            "план", "Наименование оборудования", "Тип оборудования", "Производитель оборудования",
            "Наименование мероприятия", "Шифр расценки", "Наименование расценки",
            "периодичность операции", "обоснование периодичности", "количество операций в год",
            "количество оборудования", "выполнений в год",
            "ед. измер. (на какой объем оборудования рассчитана расценка)", "Всего ед. измер.",
            "Цена на единицу измерения: ЗП", "Цена на ед. измерения: ЭМ",
            "в том числе оплата труда машинистов", "Цена на ед. измер.: МР", "Поправочный коэффициент",
            "Всего ЗП:", "Всего: ЭМ", "в том числе ЗПМ", "Всего МР:", "НР", "НП",
            "Итого в год (без НДС)", "НДС", "Итого в год (с НДС)",
            // блок РТ
            "Цена на единицу измерения: ЗП", "Цена на ед. измерения: ЭМ",
            "в том числе оплата труда машинистов", "Цена на ед. измер.: МР",
            "Всего ЗП:", "Всего: ЭМ", "в том числе ЗПМ", "Всего МР:", "НР", "НП",
            "Итого в год (без НДС)", "НДС", "Итого в год (с НДС)",
            "Справочно: затраты труда человеко-часов (на ед. измерения)",
            "Справочно: затраты труда человеко-часов (Общее за год)",
            "разметка отклонений", "обоснование расценки"
    };

    /**
     * Ширины колонок в символах — компактные: длинные тексты переносятся по строкам,
     * поэтому колонки не нужно делать под всю длину наименования. Закреплённый блок
     * (первые 6 колонок) в сумме ~93 символа, чтобы оставлять место расчёту.
     */
    private static final int[] WIDTHS = {
            5, 26, 13, 12, 22, 15, 28, 11, 16, 8, 8, 8, 8, 9,
            10, 10, 10, 10, 8, 11, 10, 10, 10, 11, 10, 12, 10, 12,
            10, 10, 10, 10, 11, 10, 10, 10, 11, 10, 12, 10, 12, 9, 10,
            13, 30
    };

    /** Последняя колонка расчёта (AS «обоснование расценки»). */
    private static final int LAST_COL = HEADERS.length - 1;

    /** Сколько колонок держать на виду при прокрутке: до шифра расценки включительно. */
    private static final int FROZEN_COLUMNS = 6;

    private static final String BLUE = "1F4E78";    // блок СН-2012
    private static final String GREEN = "00B050";   // блок РТ
    private static final String AMBER = "FFC000";   // расчётные колонки
    private static final String MONEY = "_-* #,##0.00_-;\\-* #,##0.00_-;_-* \\-??_-;_-@_-";
    /** Лист параметров: НР/НП/НДС/коэффициент РТ/площадь — на него ссылаются все формулы. */
    private static final String DATA_SHEET = "Данные для расчета";
    /** Лист-справочник расценок: цены и трудозатраты, которые тянет ВПР со листа расчёта. */
    private static final String RATES_SHEET = "Справочник СН-2012";
    /** Сводная: оборудование ↔ расценка осмотра ↔ расценка ТО ↔ периодичность. */
    private static final String SUMMARY_SHEET = "Сводная таблица";

    /** Янтарные колонки (как в эталоне): поправочный коэффициент и «Всего ЗП». */
    private static boolean amber(int col) {
        return col == 18 || col == 19 || col == 20 || col == 21 || col == 32;
    }

    private final EstimateCalculator calculator;

    public EstimateXlsxExporter(EstimateCalculator calculator) {
        this.calculator = calculator;
    }

    /** Стили книги — создаются один раз (POI ограничивает число стилей). */
    private static final class Styles {
        CellStyle title;
        CellStyle headBlue, headGreen, headAmber;
        CellStyle text, textWrap, center, money, intNum;
        CellStyle section;
        CellStyle totalLabel, totalMoney;
        CellStyle markChanged, markAdded;
    }

    /** Запись «Истории версий»: номер выгрузки и когда она сделана. */
    public record Version(int number, java.time.Instant exportedAt) {
        /** Подпись версии: 1 → v1, 2 → v1_1, 3 → v1_2 (как в эталоне). */
        public String label() {
            return number <= 1 ? "v1" : "v1_" + (number - 1);
        }
    }

    public byte[] export(Estimate estimate, java.util.List<EstimateRow> rows) throws Exception {
        return export(estimate, rows, null);
    }

    /** areaSqm — площадь объекта, м² (для расценок с измерителем в м²); может быть null. */
    public byte[] export(Estimate estimate, java.util.List<EstimateRow> rows, BigDecimal areaSqm) throws Exception {
        return export(estimate, rows, areaSqm, java.util.List.of());
    }

    /**
     * Полная выгрузка: расчёт (формулами), сводная таблица, справочник расценок для ВПР,
     * лист параметров с историей версий.
     */
    public byte[] export(Estimate estimate, java.util.List<EstimateRow> rows, BigDecimal areaSqm,
                         java.util.List<Version> history) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = buildStyles(wb);
            writeDataSheet(wb, st, estimate, areaSqm, history);   // параметры — на них ссылаются формулы
            java.util.Map<String, RateRef> rates = collectRates(rows);
            writeRatesSheet(wb, st, rates);                        // база для ВПР
            writeSummarySheet(wb, st, rows);
            writeCalcSheet(wb, st, estimate, rows, rates);
            wb.setSheetOrder("Расчёт СН-2012", 0);       // расчёт показываем первым
            wb.setSheetOrder(SUMMARY_SHEET, 1);
            wb.setSheetOrder(RATES_SHEET, 2);
            // считаем формулы, чтобы в файле были и формулы, и готовые значения
            XSSFFormulaEvaluator.evaluateAllFormulaCells(wb);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private Styles buildStyles(XSSFWorkbook wb) {
        Styles s = new Styles();
        short fmt = wb.createDataFormat().getFormat(MONEY);

        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        s.title = wb.createCellStyle();
        s.title.setFont(titleFont);
        s.title.setVerticalAlignment(VerticalAlignment.CENTER);

        Font headFont = wb.createFont();
        headFont.setBold(true);
        headFont.setFontHeightInPoints((short) 10);
        headFont.setColor(IndexedColors.WHITE.getIndex());
        s.headBlue = headStyle(wb, headFont, BLUE);
        s.headGreen = headStyle(wb, headFont, GREEN);
        s.headAmber = headStyle(wb, headFont, AMBER);

        s.text = bordered(wb);
        s.textWrap = bordered(wb);
        s.textWrap.setWrapText(true);
        s.textWrap.setVerticalAlignment(VerticalAlignment.TOP);
        s.center = bordered(wb);
        s.center.setAlignment(HorizontalAlignment.CENTER);
        s.center.setVerticalAlignment(VerticalAlignment.CENTER);
        s.money = bordered(wb);
        s.money.setDataFormat(fmt);
        s.intNum = bordered(wb);
        s.intNum.setAlignment(HorizontalAlignment.CENTER);

        Font secFont = wb.createFont();
        secFont.setBold(true);
        s.section = wb.createCellStyle();
        s.section.setFont(secFont);
        s.section.setFillForegroundColor(new XSSFColor(rgb("D9E1F2"), null));
        s.section.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.section.setVerticalAlignment(VerticalAlignment.CENTER);

        Font totalFont = wb.createFont();
        totalFont.setBold(true);
        s.totalLabel = bordered(wb);
        s.totalLabel.setFont(totalFont);
        s.totalMoney = bordered(wb);
        s.totalMoney.setFont(totalFont);
        s.totalMoney.setDataFormat(fmt);

        // разметка отклонений от эталона: жёлтый — изменено, зелёный — добавлено
        s.markChanged = fill(wb, "FFF2CC");
        s.markAdded = fill(wb, "E2EFDA");
        return s;
    }

    private CellStyle headStyle(XSSFWorkbook wb, Font font, String hex) {
        XSSFCellStyle st = wb.createCellStyle();
        st.setFont(font);
        st.setFillForegroundColor(new XSSFColor(rgb(hex), null));
        st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        st.setAlignment(HorizontalAlignment.CENTER);
        st.setVerticalAlignment(VerticalAlignment.CENTER);
        st.setWrapText(true);
        border(st);
        return st;
    }

    /** Ячейка с заливкой (для разметки отклонений). */
    private CellStyle fill(XSSFWorkbook wb, String hex) {
        XSSFCellStyle st = (XSSFCellStyle) bordered(wb);
        st.setFillForegroundColor(new XSSFColor(rgb(hex), null));
        st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        st.setAlignment(HorizontalAlignment.CENTER);
        return st;
    }

    private CellStyle bordered(XSSFWorkbook wb) {
        CellStyle st = wb.createCellStyle();
        border(st);
        return st;
    }

    private static void border(CellStyle st) {
        st.setBorderTop(BorderStyle.THIN);
        st.setBorderBottom(BorderStyle.THIN);
        st.setBorderLeft(BorderStyle.THIN);
        st.setBorderRight(BorderStyle.THIN);
    }

    private static byte[] rgb(String hex) {
        return new byte[]{
                (byte) Integer.parseInt(hex.substring(0, 2), 16),
                (byte) Integer.parseInt(hex.substring(2, 4), 16),
                (byte) Integer.parseInt(hex.substring(4, 6), 16)};
    }

    private void writeDataSheet(Workbook wb, Styles st, Estimate e, BigDecimal areaSqm,
                                java.util.List<Version> history) {
        Sheet sheet = wb.createSheet(DATA_SHEET);
        sheet.setColumnWidth(0, 52 * 256);
        sheet.setColumnWidth(1, 20 * 256);
        sheet.setColumnWidth(2, 40 * 256);
        Row head = sheet.createRow(0);
        Cell h = head.createCell(0);
        h.setCellValue("Вспомогательные данные для расчета сметы");
        h.setCellStyle(st.title);
        putKv(sheet, 1, "Накладные расходы, в % от ЗП", e.getNrZp());
        putKv(sheet, 2, "Нормативная прибыль, в % от ЗП", e.getNpZp());
        putKv(sheet, 3, "Накладные расходы, в % от затрат на эксплуатацию", e.getNrEm());
        putKv(sheet, 4, "Нормативная прибыль, в % от затрат на эксплуатацию", e.getNpEm());
        putKv(sheet, 5, "НДС", e.getVat());
        putKv(sheet, 6, "Коэффициент перехода в уровень РТ", e.getRtCoefficient());
        // площадь нужна расценкам с измерителем в м² (напр. проверка работоспособности СПЗ)
        putKv(sheet, 7, "Площадь объекта, м²", areaSqm);

        // История версий: каждая выгрузка — отдельная строка (v1 → v1_1 → v1_2)
        Row title = sheet.createRow(9);
        Cell t = title.createCell(0);
        t.setCellValue("История версий");
        t.setCellStyle(st.title);
        Row header = sheet.createRow(10);
        String[] cols = {"Версия", "Дата выгрузки", "Смета"};
        for (int c = 0; c < cols.length; c++) {
            Cell hc = header.createCell(c);
            hc.setCellValue(cols[c]);
            hc.setCellStyle(st.headBlue);
        }
        int r = 11;
        for (Version v : history) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(v.label());
            row.createCell(1).setCellValue(v.exportedAt() == null ? "" : DATE_TIME.format(v.exportedAt()));
            row.createCell(2).setCellValue(e.getName() == null ? "" : e.getName());
        }
    }

    private static final java.time.format.DateTimeFormatter DATE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());

    private void putKv(Sheet sheet, int rowIdx, String label, BigDecimal value) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(label);
        if (value != null) row.createCell(1).setCellValue(value.doubleValue());
    }

    /** Расценка справочника: цены и трудозатраты, одинаковые для всех строк с этим шифром. */
    private record RateRef(String code, String name, BigDecimal basis, BigDecimal zp, BigDecimal em,
                           BigDecimal zpm, BigDecimal mr, BigDecimal laborHours, int row) {}

    /**
     * Справочник расценок сметы: по одной записи на шифр (первое вхождение).
     * Строки, у которых цены отличаются от справочника (инженер правил вручную),
     * ВПР не используют — у них останутся собственные значения.
     */
    private java.util.Map<String, RateRef> collectRates(java.util.List<EstimateRow> rows) {
        java.util.Map<String, RateRef> rates = new java.util.LinkedHashMap<>();
        int excelRow = 2;   // строка 1 — шапка справочника
        for (EstimateRow r : rows) {
            String code = r.getRateCode();
            if (code == null || code.isBlank() || rates.containsKey(code)) continue;
            rates.put(code, new RateRef(code, r.getRateName(), r.getUnitBasis(), r.getPriceZp(),
                    r.getPriceEm(), r.getPriceZpm(), r.getPriceMr(), r.getLaborHours(), excelRow++));
        }
        return rates;
    }

    private void writeRatesSheet(Workbook wb, Styles st, java.util.Map<String, RateRef> rates) {
        Sheet sheet = wb.createSheet(RATES_SHEET);
        String[] cols = {"Шифр расценки", "Наименование расценки", "Измеритель (ед. оборудования)",
                "ЗП", "ЭМ", "в т.ч. ЗПМ", "МР", "Затраты труда, чел.-ч"};
        int[] widths = {17, 60, 14, 11, 11, 11, 11, 13};
        Row header = sheet.createRow(0);
        header.setHeightInPoints(30);
        for (int c = 0; c < cols.length; c++) {
            Cell hc = header.createCell(c);
            hc.setCellValue(cols[c]);
            hc.setCellStyle(st.headBlue);
            sheet.setColumnWidth(c, widths[c] * 256);
        }
        int r = 1;
        for (RateRef ref : rates.values()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, ref.code(), st.center);
            cell(row, 1, ref.name(), st.textWrap);
            num(row, 2, ref.basis(), st.intNum);
            num(row, 3, ref.zp(), st.money);
            num(row, 4, ref.em(), st.money);
            num(row, 5, ref.zpm(), st.money);
            num(row, 6, ref.mr(), st.money);
            num(row, 7, ref.laborHours(), st.money);
        }
        sheet.createFreezePane(1, 1);
    }

    /**
     * Сводная таблица: по оборудованию — расценка осмотра, расценка ТО и периодичности.
     * Это первое, что смотрит проверяющий: видно, что у каждого изделия закрыты обе работы.
     */
    private void writeSummarySheet(Workbook wb, Styles st, java.util.List<EstimateRow> rows) {
        Sheet sheet = wb.createSheet(SUMMARY_SHEET);
        String[] cols = {"Система", "Оборудование", "Тип / модель", "Кол-во",
                "Осмотр: шифр", "Осмотр: периодичность", "ТО: шифр", "ТО: периодичность",
                "Прочие работы"};
        int[] widths = {18, 34, 20, 8, 17, 20, 17, 20, 30};
        Row header = sheet.createRow(0);
        header.setHeightInPoints(30);
        for (int c = 0; c < cols.length; c++) {
            Cell hc = header.createCell(c);
            hc.setCellValue(cols[c]);
            hc.setCellStyle(st.headBlue);
            sheet.setColumnWidth(c, widths[c] * 256);
        }

        // группируем по (раздел, оборудование, модель) — порядок строк сметы сохраняем
        record Key(String section, String name, String type) {}
        java.util.Map<Key, java.util.List<EstimateRow>> groups = new java.util.LinkedHashMap<>();
        for (EstimateRow r : rows) {
            groups.computeIfAbsent(new Key(r.getSection(), r.getEquipmentName(), r.getEquipmentType()),
                    k -> new java.util.ArrayList<>()).add(r);
        }

        int n = 1;
        for (var e : groups.entrySet()) {
            Row row = sheet.createRow(n++);
            cell(row, 0, e.getKey().section(), st.text);
            cell(row, 1, e.getKey().name(), st.textWrap);
            cell(row, 2, e.getKey().type(), st.text);
            num(row, 3, e.getValue().get(0).getQty(), st.intNum);
            java.util.List<String> other = new java.util.ArrayList<>();
            for (EstimateRow r : e.getValue()) {
                String category = EstimateDecisionService.operationKey(r.getOperationName());
                int col = category.startsWith("осмотр") ? 4 : category.matches("то\\d?") ? 6 : -1;
                if (col < 0 || row.getCell(col) != null) {
                    // третья и далее работа той же категории — в «прочие», чтобы ничего не потерять
                    other.add(r.getRateCode() + " (" + nz(r.getPeriodicity()) + ")");
                    continue;
                }
                cell(row, col, r.getRateCode(), st.center);
                cell(row, col + 1, r.getPeriodicity(), st.textWrap);
            }
            cell(row, 8, other.isEmpty() ? null : String.join("; ", other), st.textWrap);
        }
        sheet.createFreezePane(2, 1);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void writeCalcSheet(Workbook wb, Styles st, Estimate estimate, java.util.List<EstimateRow> rows,
                                java.util.Map<String, RateRef> rates) {
        Sheet sheet = wb.createSheet("Расчёт СН-2012");

        // строка-заголовок: название сметы и подписи блоков (как в эталоне)
        Row title = sheet.createRow(0);
        title.setHeightInPoints(22);
        cell(title, 1, estimate.getName(), st.title);
        cell(title, 14, "Расчет в сметных нормативах сборника СН-2012", st.title);
        cell(title, 28, "Расчет с учетом корректировки ЗТР в уровень РТ", st.title);

        Row header = sheet.createRow(1);
        header.setHeightInPoints(105);
        for (int c = 0; c < HEADERS.length; c++) {
            Cell hc = header.createCell(c);
            hc.setCellValue(HEADERS[c]);
            hc.setCellStyle(amber(c) ? st.headAmber : c >= 28 && c <= 40 ? st.headGreen : st.headBlue);
        }
        for (int c = 0; c < WIDTHS.length; c++) {
            sheet.setColumnWidth(c, WIDTHS[c] * 256);
        }
        sheet.createFreezePane(FROZEN_COLUMNS, 2);   // держим шапку и описание строки на виду

        var coeffs = EstimateCalculator.Coefficients.of(estimate);
        int r = 2;
        String lastSection = null;
        BigDecimal sumNoVat = BigDecimal.ZERO, sumVat = BigDecimal.ZERO, sumWithVat = BigDecimal.ZERO;
        BigDecimal sumNoVatRt = BigDecimal.ZERO, sumVatRt = BigDecimal.ZERO, sumWithVatRt = BigDecimal.ZERO;
        BigDecimal sumLabor = BigDecimal.ZERO;

        for (EstimateRow row : rows) {
            if (row.getSection() != null && !row.getSection().equals(lastSection)) {
                Row sec = sheet.createRow(r);
                sec.setHeightInPoints(18);
                for (int c = 0; c <= LAST_COL; c++) sec.createCell(c).setCellStyle(st.section);
                sec.getCell(1).setCellValue(row.getSection());
                sheet.addMergedRegion(new CellRangeAddress(r, r, 1, 8));
                lastSection = row.getSection();
                r++;
            }
            EstimateCalculator.RowResult calc = calculator.compute(row, coeffs);
            writeRow(sheet.createRow(r++), st, row, rates);

            sumNoVat = sumNoVat.add(calc.totalNoVat());
            sumVat = sumVat.add(calc.vat());
            sumWithVat = sumWithVat.add(calc.totalWithVat());
            sumNoVatRt = sumNoVatRt.add(calc.totalNoVatRt());
            sumVatRt = sumVatRt.add(calc.vatRt());
            sumWithVatRt = sumWithVatRt.add(calc.totalWithVatRt());
            sumLabor = sumLabor.add(calc.laborHoursTotal());
        }

        Row total = sheet.createRow(r);
        total.setHeightInPoints(18);
        for (int c = 0; c <= LAST_COL; c++) total.createCell(c).setCellStyle(st.totalLabel);
        total.getCell(1).setCellValue("ИТОГО в год");
        sheet.addMergedRegion(new CellRangeAddress(r, r, 1, 8));
        // итоги — СУММ() по строкам расчёта, а не числом: пересчитываются вместе со строками
        int firstDataRow = 3, lastDataRow = r;   // 1-based; строки-разделы пусты и в сумму не мешают
        for (int col : new int[]{19, 20, 21, 22, 23, 24, 25, 26, 27,
                                 32, 33, 34, 35, 36, 37, 38, 39, 40, 42}) {
            String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(col);
            Cell c = total.getCell(col);
            c.setCellFormula("SUM(" + letter + firstDataRow + ":" + letter + lastDataRow + ")");
            c.setCellStyle(st.totalMoney);
        }
    }

    /**
     * Строка расчёта: входные величины — значениями, все производные колонки — ФОРМУЛАМИ
     * со ссылками на параметры листа «Данные для расчета» ($B$2…$B$7). Файл остаётся
     * живым: меняются количество, коэффициент или НДС — Excel пересчитывает сам.
     */
    private void writeRow(Row row, Styles st, EstimateRow src, java.util.Map<String, RateRef> rates) {
        int n = row.getRowNum() + 1;   // номер строки в адресах Excel (1-based)
        cell(row, 0, src.getPosition() == null ? null : String.valueOf(src.getPosition()), st.center);
        cell(row, 1, src.getEquipmentName(), st.textWrap);
        cell(row, 2, src.getEquipmentType(), st.text);
        cell(row, 3, src.getManufacturer(), st.text);
        cell(row, 4, src.getOperationName(), st.textWrap);
        cell(row, 5, src.getRateCode(), st.center);
        // цены и трудозатраты — ВПР по шифру из «Справочника СН-2012»; если инженер
        // правил цены руками и они разошлись со справочником, оставляем его значения
        boolean lookup = matchesReference(src, rates.get(src.getRateCode()));
        if (lookup) formula(row, 6, vlookup(n, 2, true), st.textWrap);
        else cell(row, 6, src.getRateName(), st.textWrap);
        cell(row, 7, src.getPeriodicity(), st.center);
        cell(row, 8, src.getJustification(), st.textWrap);
        // входные величины
        num(row, 9, src.getOpsPerYear(), st.intNum);
        num(row, 10, src.getQty(), st.intNum);
        formula(row, 11, "K" + n + "*J" + n, st.intNum);                       // выполнений в год
        if (lookup) formula(row, 12, vlookup(n, 3, false), st.intNum);
        else num(row, 12, src.getUnitBasis(), st.intNum);
        formula(row, 13, "IFERROR(L" + n + "/M" + n + ",0)", st.money);        // всего ед. измер.
        if (lookup) {
            formula(row, 14, vlookup(n, 4, false), st.money);
            formula(row, 15, vlookup(n, 5, false), st.money);
            formula(row, 16, vlookup(n, 6, false), st.money);
            formula(row, 17, vlookup(n, 7, false), st.money);
        } else {
            num(row, 14, src.getPriceZp(), st.money);
            num(row, 15, src.getPriceEm(), st.money);
            num(row, 16, src.getPriceZpm(), st.money);
            num(row, 17, src.getPriceMr(), st.money);
        }
        num(row, 18, src.getCorrection(), st.intNum);
        // блок СН-2012
        formula(row, 19, "O" + n + "*N" + n + "*S" + n, st.money);             // всего ЗП
        formula(row, 20, "P" + n + "*N" + n + "*S" + n, st.money);             // всего ЭМ
        formula(row, 21, "Q" + n + "*N" + n + "*S" + n, st.money);             // в т.ч. ЗПМ
        formula(row, 22, "R" + n + "*N" + n, st.money);                        // всего МР (без коэф.)
        formula(row, 23, "T" + n + "*" + p("B", 2) + "+V" + n + "*" + p("B", 4), st.money);  // НР
        formula(row, 24, "T" + n + "*" + p("B", 3) + "+V" + n + "*" + p("B", 5), st.money);  // НП
        formula(row, 25, "T" + n + "+U" + n + "+W" + n + "+X" + n + "+Y" + n, st.money);
        formula(row, 26, "ROUND(Z" + n + "*" + p("B", 6) + ",2)", st.money);   // НДС
        formula(row, 27, "Z" + n + "+AA" + n, st.money);
        // блок РТ: цены на единицу тоже формулами — их ищет проверяющий
        formula(row, 28, "IFERROR(O" + n + "/" + p("B", 7) + ",0)", st.money);
        formula(row, 29, "(P" + n + "-Q" + n + ")+AE" + n, st.money);
        formula(row, 30, "IFERROR(Q" + n + "/" + p("B", 7) + ",0)", st.money);
        formula(row, 31, "R" + n, st.money);
        formula(row, 32, "AC" + n + "*N" + n + "*S" + n, st.money);
        formula(row, 33, "AD" + n + "*N" + n + "*S" + n, st.money);
        formula(row, 34, "AE" + n + "*N" + n + "*S" + n, st.money);
        formula(row, 35, "AF" + n + "*N" + n, st.money);                       // МР без коэф.
        formula(row, 36, "AG" + n + "*" + p("B", 2) + "+AI" + n + "*" + p("B", 4), st.money);
        formula(row, 37, "AG" + n + "*" + p("B", 3) + "+AI" + n + "*" + p("B", 5), st.money);
        formula(row, 38, "AG" + n + "+AH" + n + "+AJ" + n + "+AK" + n + "+AL" + n, st.money);
        formula(row, 39, "ROUND(AM" + n + "*" + p("B", 6) + ",2)", st.money);
        formula(row, 40, "AM" + n + "+AN" + n, st.money);
        if (lookup) formula(row, 41, vlookup(n, 8, false), st.money);
        else num(row, 41, src.getLaborHours(), st.money);
        formula(row, 42, "N" + n + "*AP" + n, st.money);                       // трудозатраты за год
        // AR — разметка отклонений от эталона, AS — служебное обоснование расценки
        cell(row, 43, deviation(src.getMatchSource()), deviationStyle(st, src.getMatchSource()));
        cell(row, 44, src.getMatchNote(), st.textWrap);
    }

    /**
     * Разметка отклонений от эталона (колонка AR). Строки, взятые из памяти эталонных
     * решений, отклонением не являются; подобранное приложением — «добавлено»,
     * поправленное инженером — «изменено».
     */
    private static String deviation(String matchSource) {
        if (matchSource == null) return null;
        return switch (matchSource) {
            case "LEARNED", "ETALON_TYPE", "ETALON_XSYS", "AI_TYPE" -> null;   // как в эталоне
            case "MANUAL", "CHOICE" -> "изменено";
            default -> "добавлено";                                            // AI / CATALOG / SYSTEM
        };
    }

    private CellStyle deviationStyle(Styles st, String matchSource) {
        String mark = deviation(matchSource);
        if (mark == null) return st.text;
        return "изменено".equals(mark) ? st.markChanged : st.markAdded;
    }

    /**
     * ВПР по шифру расценки (колонка F) в «Справочник СН-2012».
     * {@code column} — номер колонки справочника, {@code text} — вернуть «» вместо 0.
     */
    private static String vlookup(int n, int column, boolean text) {
        return "IFERROR(VLOOKUP($F" + n + ",'" + RATES_SHEET + "'!$A:$H," + column + ",0),"
                + (text ? "\"\"" : "0") + ")";
    }

    /**
     * Цены строки совпадают со справочником — значит ВПР вернёт ровно те же числа
     * и формулу подставлять безопасно. Расхождение (ручная правка цен) оставляем
     * значениями, чтобы выгрузка не переписала работу инженера.
     */
    private static boolean matchesReference(EstimateRow src, RateRef ref) {
        return ref != null
                && java.util.Objects.equals(src.getRateName(), ref.name())
                && same(src.getUnitBasis(), ref.basis())
                && same(src.getPriceZp(), ref.zp())
                && same(src.getPriceEm(), ref.em())
                && same(src.getPriceZpm(), ref.zpm())
                && same(src.getPriceMr(), ref.mr())
                && same(src.getLaborHours(), ref.laborHours());
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** Абсолютная ссылка на параметр листа «Данные для расчета». */
    private static String p(String col, int excelRow) {
        return "'" + DATA_SHEET + "'!$" + col + "$" + excelRow;
    }

    private void formula(Row row, int col, String formula, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellFormula(formula);
        c.setCellStyle(style);
    }

    private void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value != null) c.setCellValue(value);
        c.setCellStyle(style);
    }

    private void num(Row row, int col, BigDecimal value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value != null) c.setCellValue(value.doubleValue());
        c.setCellStyle(style);
    }

}
