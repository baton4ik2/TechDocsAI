package ru.techdocs.estimate;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

/** Экспорт сметы в XLSX в формате эталонного расчёта (43 колонки + лист коэффициентов). */
@Service
public class EstimateXlsxExporter {

    private static final String[] HEADERS = {
            "план", "Наименование оборудования", "Тип оборудования", "Производитель оборудования",
            "Наименование мероприятия", "Шифр расценки", "Наименование расценки",
            "периодичность операции", "обоснование периодичности", "количество операций в год",
            "количество оборудования", "выполнений в год",
            "ед. измер. (объём расценки)", "Всего ед. измер.",
            "Цена ед.: ЗП", "Цена ед.: ЭМ", "в т.ч. ЗПМ", "Цена ед.: МР", "Поправочный коэффициент",
            "Всего ЗП", "Всего ЭМ", "в т.ч. ЗПМ", "Всего МР", "НР", "НП",
            "Итого в год (без НДС)", "НДС", "Итого в год (с НДС)",
            // блок РТ
            "РТ: Цена ед. ЗП", "РТ: Цена ед. ЭМ", "РТ: в т.ч. ЗПМ", "РТ: Цена ед. МР",
            "РТ: Всего ЗП", "РТ: Всего ЭМ", "РТ: в т.ч. ЗПМ", "РТ: Всего МР", "РТ: НР", "РТ: НП",
            "РТ: Итого (без НДС)", "РТ: НДС", "РТ: Итого (с НДС)",
            "Справочно: труд чел-ч на ед.", "Справочно: труд чел-ч за год"
    };

    private final EstimateCalculator calculator;

    public EstimateXlsxExporter(EstimateCalculator calculator) {
        this.calculator = calculator;
    }

    public byte[] export(Estimate estimate, java.util.List<EstimateRow> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeDataSheet(wb, estimate);
            writeCalcSheet(wb, estimate, rows);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void writeDataSheet(Workbook wb, Estimate e) {
        Sheet sheet = wb.createSheet("Данные для расчёта");
        putKv(sheet, 0, "Накладные расходы, в % от ЗП", e.getNrZp());
        putKv(sheet, 1, "Нормативная прибыль, в % от ЗП", e.getNpZp());
        putKv(sheet, 2, "Накладные расходы, в % от ЭМ", e.getNrEm());
        putKv(sheet, 3, "Нормативная прибыль, в % от ЭМ (ЗПМ)", e.getNpEm());
        putKv(sheet, 4, "НДС", e.getVat());
        putKv(sheet, 5, "Коэффициент перехода в уровень РТ", e.getRtCoefficient());
    }

    private void putKv(Sheet sheet, int rowIdx, String label, BigDecimal value) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(label);
        if (value != null) row.createCell(1).setCellValue(value.doubleValue());
    }

    private void writeCalcSheet(Workbook wb, Estimate estimate, java.util.List<EstimateRow> rows) {
        Sheet sheet = wb.createSheet("Расчёт СН-2012");
        CellStyle bold = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        bold.setFont(f);

        Row header = sheet.createRow(0);
        for (int c = 0; c < HEADERS.length; c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(HEADERS[c]);
            cell.setCellStyle(bold);
        }

        var coeffs = EstimateCalculator.Coefficients.of(estimate);
        int r = 1;
        String lastSection = null;
        BigDecimal sumNoVat = BigDecimal.ZERO, sumVat = BigDecimal.ZERO, sumWithVat = BigDecimal.ZERO;
        BigDecimal sumNoVatRt = BigDecimal.ZERO, sumVatRt = BigDecimal.ZERO, sumWithVatRt = BigDecimal.ZERO;
        BigDecimal sumLabor = BigDecimal.ZERO;

        for (EstimateRow row : rows) {
            if (row.getSection() != null && !row.getSection().equals(lastSection)) {
                Row sec = sheet.createRow(r++);
                Cell c = sec.createCell(1);
                c.setCellValue(row.getSection());
                c.setCellStyle(bold);
                lastSection = row.getSection();
            }
            EstimateCalculator.RowResult calc = calculator.compute(row, coeffs);
            writeRow(sheet.createRow(r++), row, calc);

            sumNoVat = sumNoVat.add(calc.totalNoVat());
            sumVat = sumVat.add(calc.vat());
            sumWithVat = sumWithVat.add(calc.totalWithVat());
            sumNoVatRt = sumNoVatRt.add(calc.totalNoVatRt());
            sumVatRt = sumVatRt.add(calc.vatRt());
            sumWithVatRt = sumWithVatRt.add(calc.totalWithVatRt());
            sumLabor = sumLabor.add(calc.laborHoursTotal());
        }

        Row total = sheet.createRow(r);
        Cell label = total.createCell(1);
        label.setCellValue("ИТОГО");
        label.setCellStyle(bold);
        num(total, 25, sumNoVat);
        num(total, 26, sumVat);
        num(total, 27, sumWithVat);
        num(total, 38, sumNoVatRt);
        num(total, 39, sumVatRt);
        num(total, 40, sumWithVatRt);
        num(total, 42, sumLabor);
    }

    private void writeRow(Row row, EstimateRow src, EstimateCalculator.RowResult calc) {
        str(row, 0, src.getPosition() == null ? null : String.valueOf(src.getPosition()));
        str(row, 1, src.getEquipmentName());
        str(row, 2, src.getEquipmentType());
        str(row, 3, src.getManufacturer());
        str(row, 4, src.getOperationName());
        str(row, 5, src.getRateCode());
        str(row, 6, src.getRateName());
        str(row, 7, src.getPeriodicity());
        str(row, 8, src.getJustification());
        num(row, 9, src.getOpsPerYear());
        num(row, 10, src.getQty());
        num(row, 11, calc.performedPerYear());
        num(row, 12, src.getUnitBasis());
        num(row, 13, calc.totalUnits());
        num(row, 14, src.getPriceZp());
        num(row, 15, src.getPriceEm());
        num(row, 16, src.getPriceZpm());
        num(row, 17, src.getPriceMr());
        num(row, 18, src.getCorrection());
        num(row, 19, calc.zp());
        num(row, 20, calc.em());
        num(row, 21, calc.zpm());
        num(row, 22, calc.mr());
        num(row, 23, calc.nr());
        num(row, 24, calc.np());
        num(row, 25, calc.totalNoVat());
        num(row, 26, calc.vat());
        num(row, 27, calc.totalWithVat());
        // блок РТ (колонки цен на единицу 28–31 — справочные, опускаем; заполняем «всего»)
        num(row, 32, calc.zpRt());
        num(row, 33, calc.emRt());
        num(row, 34, calc.zpmRt());
        num(row, 35, calc.mrRt());
        num(row, 36, calc.nrRt());
        num(row, 37, calc.npRt());
        num(row, 38, calc.totalNoVatRt());
        num(row, 39, calc.vatRt());
        num(row, 40, calc.totalWithVatRt());
        num(row, 41, src.getLaborHours());
        num(row, 42, calc.laborHoursTotal());
    }

    private void str(Row row, int col, String value) {
        if (value != null) row.createCell(col).setCellValue(value);
    }

    private void num(Row row, int col, BigDecimal value) {
        if (value != null) row.createCell(col).setCellValue(value.doubleValue());
    }
}
