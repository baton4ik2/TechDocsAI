package ru.techdocs;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ru.techdocs.equipment.XlsxEquipmentExtractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxEquipmentExtractorTest {

    private final XlsxEquipmentExtractor extractor = new XlsxEquipmentExtractor();

    private byte[] workbook(String[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Спецификация");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsSpecificationRows() throws Exception {
        byte[] xlsx = workbook(
                new String[]{"№", "Производитель", "Наименование", "Модель", "Ед. изм.", "Кол-во"},
                new String[]{"1", "Болид", "Извещатель дымовой", "ДИП-34А", "шт.", "186"},
                new String[]{"2", "Рубеж", "Извещатель ручной", "ИПР 513-11", "шт.", "24"});

        List<XlsxEquipmentExtractor.ExtractedItem> items = extractor.extract("spec.xlsx",
                new ByteArrayInputStream(xlsx));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).manufacturer()).isEqualTo("Болид");
        assertThat(items.get(0).name()).isEqualTo("Извещатель дымовой");
        assertThat(items.get(0).model()).isEqualTo("ДИП-34А");
        assertThat(items.get(0).quantity()).isEqualByComparingTo(new BigDecimal("186"));
        assertThat(items.get(1).quantity()).isEqualByComparingTo(new BigDecimal("24"));
    }

    @Test
    void skipsRowsWithoutQuantity() throws Exception {
        byte[] xlsx = workbook(
                new String[]{"Наименование", "Модель", "Кол-во"},
                new String[]{"Прибор с количеством", "П-1", "5"},
                new String[]{"Заголовок раздела", "", ""},
                new String[]{"Прибор без количества", "П-2", ""});

        List<XlsxEquipmentExtractor.ExtractedItem> items = extractor.extract("spec.xlsx",
                new ByteArrayInputStream(xlsx));

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().name()).isEqualTo("Прибор с количеством");
    }

    @Test
    void returnsEmptyWhenNoRecognizableTable() throws Exception {
        byte[] xlsx = workbook(
                new String[]{"Просто", "какой-то", "текст"},
                new String[]{"без", "таблицы", "оборудования"});

        List<XlsxEquipmentExtractor.ExtractedItem> items = extractor.extract("spec.xlsx",
                new ByteArrayInputStream(xlsx));

        assertThat(items).isEmpty();
    }

    @Test
    void parsesQuantityWithSpacesAndComma() throws Exception {
        byte[] xlsx = workbook(
                new String[]{"Наименование", "Кол-во"},
                new String[]{"Кабель", "1 044"});

        List<XlsxEquipmentExtractor.ExtractedItem> items = extractor.extract("spec.xlsx",
                new ByteArrayInputStream(xlsx));

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().quantity()).isEqualByComparingTo(new BigDecimal("1044"));
    }
}
