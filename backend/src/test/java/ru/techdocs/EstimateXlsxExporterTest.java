package ru.techdocs;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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

class EstimateXlsxExporterTest {

    private final EstimateXlsxExporter exporter = new EstimateXlsxExporter(new EstimateCalculator());

    @Test
    void exportsCalcSheetWithComputedValuesAndTotals() throws Exception {
        Estimate estimate = new Estimate();
        estimate.setName("Смета СКУД");

        EstimateRow row = new EstimateRow();
        row.setPosition(1);
        row.setEquipmentName("Сервер СКУД");
        row.setRateCode("22-2203-113-1/1");
        row.setOpsPerYear(new BigDecimal("12"));
        row.setQty(BigDecimal.ONE);
        row.setUnitBasis(BigDecimal.ONE);
        row.setCorrection(BigDecimal.ONE);
        row.setPriceZp(new BigDecimal("362.26"));
        row.setPriceEm(BigDecimal.ZERO);
        row.setPriceZpm(BigDecimal.ZERO);
        row.setPriceMr(new BigDecimal("0.15"));
        row.setLaborHours(new BigDecimal("0.52"));

        byte[] xlsx = exporter.export(estimate, List.of(row));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("Данные для расчета")).isNotNull();
            Sheet calc = wb.getSheet("Расчёт СН-2012");
            assertThat(calc).isNotNull();

            // 0 — строка-заголовок расчёта, 1 — шапка, данные с 2-й (как в эталоне)
            Row data = calc.getRow(2);
            assertThat(data.getCell(5).getStringCellValue()).isEqualTo("22-2203-113-1/1"); // шифр
            assertThat(data.getCell(19).getNumericCellValue()).isEqualTo(4347.12);          // Всего ЗП
            assertThat(data.getCell(26).getNumericCellValue()).isEqualTo(1721.86);          // НДС

            Row total = calc.getRow(3);
            assertThat(total.getCell(1).getStringCellValue()).isEqualTo("ИТОГО в год");
            assertThat(total.getCell(25).getNumericCellValue()).isEqualTo(7826.616);        // без НДС
            assertThat(total.getCell(27).getNumericCellValue()).isEqualTo(9548.476);        // с НДС
        }
    }
}
