package ru.techdocs;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ru.techdocs.equipment.WideRegistryParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WideRegistryParserTest {

    private final WideRegistryParser parser = new WideRegistryParser();

    /** Строит «широкий» реестр: 2 блока-системы по 4 колонки. */
    private byte[] wideWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Реестр");
            // строка 0: названия систем в начале каждого блока
            Row sys = sheet.createRow(0);
            sys.createCell(0).setCellValue("Видеонаблюдение");
            sys.createCell(4).setCellValue("Пожарная сигнализация");
            // строка 1: заголовки колонок каждого блока
            Row head = sheet.createRow(1);
            String[] cols = {"Производитель", "Наименование", "Модель", "Кол-во"};
            for (int i = 0; i < 4; i++) {
                head.createCell(i).setCellValue(cols[i]);
                head.createCell(i + 4).setCellValue(cols[i]);
            }
            // строка 2: данные обоих блоков
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("LTV");
            r2.createCell(1).setCellValue("Камера купольная");
            r2.createCell(2).setCellValue("LTV-3CND40");
            r2.createCell(3).setCellValue("124");
            r2.createCell(4).setCellValue("Рубеж");
            r2.createCell(5).setCellValue("Извещатель дымовой");
            r2.createCell(6).setCellValue("ИП 212-64");
            r2.createCell(7).setCellValue("1524");
            // строка 3: подзаголовок «СОУЭ» внутри блока пожарной сигнализации
            Row r3 = sheet.createRow(3);
            r3.createCell(5).setCellValue("СОУЭ");
            // строка 4: позиция под подзаголовком СОУЭ
            Row r4 = sheet.createRow(4);
            r4.createCell(5).setCellValue("Громкоговоритель");
            r4.createCell(6).setCellValue("LPA-6W");
            r4.createCell(7).setCellValue("80");
            // строка 5: строка-пометка «По ИТД» — должна игнорироваться
            Row r5 = sheet.createRow(5);
            r5.createCell(1).setCellValue("По ИТД");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void mapsBlocksToSystems() throws Exception {
        List<WideRegistryParser.WideItem> items = parser.parse("реестр.xlsx",
                new ByteArrayInputStream(wideWorkbook()));

        // камера в Видеонаблюдение, извещатель в Пожарную, громкоговоритель в СОУЭ
        WideRegistryParser.WideItem camera = items.stream()
                .filter(i -> i.name().contains("Камера")).findFirst().orElseThrow();
        assertThat(camera.systemName()).isEqualTo("Видеонаблюдение");
        assertThat(camera.model()).isEqualTo("LTV-3CND40");

        WideRegistryParser.WideItem izv = items.stream()
                .filter(i -> i.name().contains("Извещатель")).findFirst().orElseThrow();
        assertThat(izv.systemName()).isEqualTo("Пожарная сигнализация");
    }

    @Test
    void detectsSubsystemAsSeparateSystem() throws Exception {
        List<WideRegistryParser.WideItem> items = parser.parse("реестр.xlsx",
                new ByteArrayInputStream(wideWorkbook()));

        // громкоговоритель должен попасть в подраздел СОУЭ, а не в «Пожарную сигнализацию»
        WideRegistryParser.WideItem gromko = items.stream()
                .filter(i -> i.name().contains("Громкоговоритель")).findFirst().orElseThrow();
        assertThat(gromko.systemName()).isEqualTo("СОУЭ");
        assertThat(gromko.model()).isEqualTo("LPA-6W");
    }

    @Test
    void ignoresNoteRows() throws Exception {
        List<WideRegistryParser.WideItem> items = parser.parse("реестр.xlsx",
                new ByteArrayInputStream(wideWorkbook()));
        assertThat(items).noneMatch(i -> i.name().equalsIgnoreCase("По ИТД"));
    }

    @Test
    void returnsEmptyForFlatSingleBlock() throws Exception {
        // одиночная плоская таблица — не широкий формат
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("Наименование");
            h.createCell(1).setCellValue("Кол-во");
            Row d = sheet.createRow(1);
            d.createCell(0).setCellValue("Прибор");
            d.createCell(1).setCellValue("5");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            List<WideRegistryParser.WideItem> items = parser.parse("flat.xlsx",
                    new ByteArrayInputStream(out.toByteArray()));
            assertThat(items).isEmpty();
        }
    }
}
