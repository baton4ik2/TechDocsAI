package ru.techdocs.estimate;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
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
            "Справочно: затраты труда человеко-часов (Общее за год)"
    };

    /**
     * Ширины колонок в символах — компактные: длинные тексты переносятся по строкам,
     * поэтому колонки не нужно делать под всю длину наименования. Закреплённый блок
     * (первые 6 колонок) в сумме ~93 символа, чтобы оставлять место расчёту.
     */
    private static final int[] WIDTHS = {
            5, 26, 13, 12, 22, 15, 28, 11, 16, 8, 8, 8, 8, 9,
            10, 10, 10, 10, 8, 11, 10, 10, 10, 11, 10, 12, 10, 12,
            10, 10, 10, 10, 11, 10, 10, 10, 11, 10, 12, 10, 12, 9, 10
    };

    /** Сколько колонок держать на виду при прокрутке: до шифра расценки включительно. */
    private static final int FROZEN_COLUMNS = 6;

    private static final String BLUE = "1F4E78";    // блок СН-2012
    private static final String GREEN = "00B050";   // блок РТ
    private static final String AMBER = "FFC000";   // расчётные колонки
    private static final String MONEY = "_-* #,##0.00_-;\\-* #,##0.00_-;_-* \\-??_-;_-@_-";

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
    }

    public byte[] export(Estimate estimate, java.util.List<EstimateRow> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = buildStyles(wb);
            writeCalcSheet(wb, st, estimate, rows);  // первым — сам расчёт
            writeDataSheet(wb, st, estimate);
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

    private void writeDataSheet(Workbook wb, Styles st, Estimate e) {
        Sheet sheet = wb.createSheet("Данные для расчета");
        sheet.setColumnWidth(0, 52 * 256);
        sheet.setColumnWidth(1, 12 * 256);
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
    }

    private void putKv(Sheet sheet, int rowIdx, String label, BigDecimal value) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(label);
        if (value != null) row.createCell(1).setCellValue(value.doubleValue());
    }

    private void writeCalcSheet(Workbook wb, Styles st, Estimate estimate, java.util.List<EstimateRow> rows) {
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
                for (int c = 0; c <= 42; c++) sec.createCell(c).setCellStyle(st.section);
                sec.getCell(1).setCellValue(row.getSection());
                sheet.addMergedRegion(new CellRangeAddress(r, r, 1, 8));
                lastSection = row.getSection();
                r++;
            }
            EstimateCalculator.RowResult calc = calculator.compute(row, coeffs);
            writeRow(sheet.createRow(r++), st, row, calc);

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
        for (int c = 0; c <= 42; c++) total.createCell(c).setCellStyle(st.totalLabel);
        total.getCell(1).setCellValue("ИТОГО в год");
        sheet.addMergedRegion(new CellRangeAddress(r, r, 1, 8));
        totalNum(total, 25, sumNoVat, st);
        totalNum(total, 26, sumVat, st);
        totalNum(total, 27, sumWithVat, st);
        totalNum(total, 38, sumNoVatRt, st);
        totalNum(total, 39, sumVatRt, st);
        totalNum(total, 40, sumWithVatRt, st);
        totalNum(total, 42, sumLabor, st);
    }

    private void writeRow(Row row, Styles st, EstimateRow src, EstimateCalculator.RowResult calc) {
        cell(row, 0, src.getPosition() == null ? null : String.valueOf(src.getPosition()), st.center);
        cell(row, 1, src.getEquipmentName(), st.textWrap);
        cell(row, 2, src.getEquipmentType(), st.text);
        cell(row, 3, src.getManufacturer(), st.text);
        cell(row, 4, src.getOperationName(), st.textWrap);
        cell(row, 5, src.getRateCode(), st.center);
        cell(row, 6, src.getRateName(), st.textWrap);
        cell(row, 7, src.getPeriodicity(), st.center);
        cell(row, 8, src.getJustification(), st.textWrap);
        num(row, 9, src.getOpsPerYear(), st.intNum);
        num(row, 10, src.getQty(), st.intNum);
        num(row, 11, calc.performedPerYear(), st.intNum);
        num(row, 12, src.getUnitBasis(), st.intNum);
        num(row, 13, calc.totalUnits(), st.money);
        num(row, 14, src.getPriceZp(), st.money);
        num(row, 15, src.getPriceEm(), st.money);
        num(row, 16, src.getPriceZpm(), st.money);
        num(row, 17, src.getPriceMr(), st.money);
        num(row, 18, src.getCorrection(), st.intNum);
        num(row, 19, calc.zp(), st.money);
        num(row, 20, calc.em(), st.money);
        num(row, 21, calc.zpm(), st.money);
        num(row, 22, calc.mr(), st.money);
        num(row, 23, calc.nr(), st.money);
        num(row, 24, calc.np(), st.money);
        num(row, 25, calc.totalNoVat(), st.money);
        num(row, 26, calc.vat(), st.money);
        num(row, 27, calc.totalWithVat(), st.money);
        // блок РТ (колонки цен на единицу 28–31 — справочные, опускаем; заполняем «всего»)
        for (int c = 28; c <= 31; c++) row.createCell(c).setCellStyle(st.money);
        num(row, 32, calc.zpRt(), st.money);
        num(row, 33, calc.emRt(), st.money);
        num(row, 34, calc.zpmRt(), st.money);
        num(row, 35, calc.mrRt(), st.money);
        num(row, 36, calc.nrRt(), st.money);
        num(row, 37, calc.npRt(), st.money);
        num(row, 38, calc.totalNoVatRt(), st.money);
        num(row, 39, calc.vatRt(), st.money);
        num(row, 40, calc.totalWithVatRt(), st.money);
        num(row, 41, src.getLaborHours(), st.money);
        num(row, 42, calc.laborHoursTotal(), st.money);
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

    private void totalNum(Row row, int col, BigDecimal value, Styles st) {
        Cell c = row.getCell(col);
        if (c == null) c = row.createCell(col);
        c.setCellValue(value.doubleValue());
        c.setCellStyle(st.totalMoney);
    }
}
