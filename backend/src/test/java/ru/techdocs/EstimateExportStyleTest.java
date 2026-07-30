package ru.techdocs;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ru.techdocs.estimate.Estimate;
import ru.techdocs.estimate.EstimateCalculator;
import ru.techdocs.estimate.EstimateRow;
import ru.techdocs.estimate.EstimateXlsxExporter;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Оформление выгрузки: шапка, цвета блоков, рамки, денежный формат, ширины, закрепление. */
class EstimateExportStyleTest {

    private final EstimateXlsxExporter exporter = new EstimateXlsxExporter(new EstimateCalculator());

    private Estimate estimate() {
        Estimate e = new Estimate();
        e.setName("Смета СКУД");
        e.setNrZp(new BigDecimal("0.70"));
        e.setNpZp(new BigDecimal("0.10"));
        e.setNrEm(new BigDecimal("0.78"));
        e.setNpEm(new BigDecimal("0.30"));
        e.setVat(new BigDecimal("0.22"));
        e.setRtCoefficient(new BigDecimal("2.62"));
        return e;
    }

    private EstimateRow row(String section, String name) {
        EstimateRow r = new EstimateRow();
        r.setSection(section);
        r.setPosition(1);
        r.setEquipmentName(name);
        r.setRateCode("22-2203-91-1/1");
        r.setPeriodicity("раз в 6 мес.");
        r.setOpsPerYear(new BigDecimal("2"));
        r.setQty(new BigDecimal("5"));
        r.setUnitBasis(BigDecimal.ONE);
        r.setCorrection(BigDecimal.ONE);
        r.setPriceZp(new BigDecimal("139.33"));
        return r;
    }

    @Test
    void exportLooksLikeReference() throws Exception {
        byte[] bytes = exporter.export(estimate(), List.of(row("АПС", "Извещатель"), row("СОУЭ", "Оповещатель")));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);

            // строка-заголовок с названием сметы и подписями блоков
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Смета СКУД");
            assertThat(sheet.getRow(0).getCell(14).getStringCellValue()).contains("СН-2012");
            assertThat(sheet.getRow(0).getCell(28).getStringCellValue()).contains("РТ");

            // шапка: перенос, центр, белый жирный на синем/зелёном/янтарном, рамки
            Row header = sheet.getRow(1);
            XSSFCellStyle blue = (XSSFCellStyle) header.getCell(1).getCellStyle();
            assertThat(blue.getWrapText()).isTrue();
            assertThat(blue.getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
            assertThat(blue.getFont().getBold()).isTrue();
            assertThat(blue.getFillForegroundColorColor().getARGBHex()).endsWith("1F4E78");
            assertThat(((XSSFCellStyle) header.getCell(32).getCellStyle())
                    .getFillForegroundColorColor().getARGBHex()).endsWith("FFC000");
            assertThat(((XSSFCellStyle) header.getCell(38).getCellStyle())
                    .getFillForegroundColorColor().getARGBHex()).endsWith("00B050");
            assertThat(blue.getBorderBottom()).isEqualTo(BorderStyle.THIN);

            // закрепление шапки и колонок-описания (до шифра расценки)
            assertThat(sheet.getPaneInformation()).isNotNull();
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 2);
            assertThat(sheet.getPaneInformation().getVerticalSplitPosition()).isEqualTo((short) 6);

            // ширины заданы и компактны: закреплённый блок не занимает пол-экрана
            int frozenWidth = 0;
            for (int c = 0; c < 6; c++) frozenWidth += sheet.getColumnWidth(c) / 256;
            assertThat(frozenWidth).isBetween(80, 100);
            // денежные колонки узкие, но читаемые
            assertThat(sheet.getColumnWidth(19) / 256).isBetween(9, 13);
            assertThat(sheet.getColumnWidth(6) / 256).isLessThanOrEqualTo(30);

            // раздел выделен и объединён
            Row section = sheet.getRow(2);
            assertThat(section.getCell(1).getStringCellValue()).isEqualTo("АПС");
            assertThat(sheet.getNumMergedRegions()).isGreaterThanOrEqualTo(2);

            // строка данных: денежный формат и рамки
            Row data = sheet.getRow(3);
            CellStyle money = data.getCell(19).getCellStyle();
            assertThat(money.getDataFormatString()).contains("#,##0.00");
            assertThat(money.getBorderLeft()).isEqualTo(BorderStyle.THIN);
            assertThat(data.getCell(1).getStringCellValue()).isEqualTo("Извещатель");

            // ИТОГО жирным с денежным форматом
            Row total = sheet.getRow(sheet.getLastRowNum());
            assertThat(total.getCell(1).getStringCellValue()).isEqualTo("ИТОГО в год");
            assertThat(((XSSFCellStyle) total.getCell(27).getCellStyle()).getFont().getBold()).isTrue();
            assertThat(total.getCell(27).getNumericCellValue()).isGreaterThan(0);

            // лист коэффициентов на месте
            assertThat(wb.getSheet("Данные для расчета")).isNotNull();
        }
    }
}
