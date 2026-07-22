package ru.techdocs;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import ru.techdocs.pkm.PkmOperation;
import ru.techdocs.pkm.PkmParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PkmParserTest {

    private final PkmParser parser = new PkmParser();

    /** Строит docx с таблицей регламента ПКМ (как в реальном файле). */
    private byte[] pkmDocx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(1, 4);
            String[] header = {"№ п/п", "Категория работ", "Вид и состав работ", "Периодичность"};
            for (int c = 0; c < 4; c++) table.getRow(0).getCell(c).setText(header[c]);

            addRow(table, "1.", "Обязательные",
                    "Техническое обслуживание устройства контроля доступа: 1. внешний осмотр состояния; " +
                            "2. очистка загрязнений; 3. проверка креплений", "Ежемесячно");
            addRow(table, "2.", "Обязательные",
                    "Проверка работоспособности системы: 1. проверка времени реакции", "Ежемесячно");
            addRow(table, "3.", "Обязательные",
                    "Сезонное обслуживание системы: 1. замена смазки", "Два раза в год");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private void addRow(XWPFTable table, String... cells) {
        var row = table.createRow();
        for (int c = 0; c < cells.length; c++) row.getCell(c).setText(cells[c]);
    }

    @Test
    void parsesOperationsWithNormalisedPeriodicity() throws Exception {
        List<PkmOperation> ops = parser.parse("reglament.docx", new ByteArrayInputStream(pkmDocx())).operations();

        assertThat(ops).hasSize(3);

        PkmOperation first = ops.get(0);
        assertThat(first.getPosition()).isEqualTo(1);
        assertThat(first.getCategory()).isEqualTo("Обязательные");
        assertThat(first.getOperationName())
                .isEqualTo("Техническое обслуживание устройства контроля доступа");
        assertThat(first.getWorkComposition())
                .contains("внешний осмотр")
                .contains("\n2.");   // пункты состава с новой строки
        assertThat(first.getPeriodicity()).isEqualTo("Ежемесячно");
        assertThat(first.getPeriodicityPerYear()).isEqualByComparingTo("12");

        assertThat(ops.get(2).getOperationName()).isEqualTo("Сезонное обслуживание системы");
        assertThat(ops.get(2).getPeriodicityPerYear()).isEqualByComparingTo("2");
    }

    @Test
    void ignoresDocumentsWithoutWorksTable() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("Колонка А");
            table.getRow(0).getCell(1).setText("Колонка Б");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            assertThat(parser.parse("x.docx", new ByteArrayInputStream(out.toByteArray())).operations()).isEmpty();
        }
    }

    @Test
    void parsesJsonRegulation() throws Exception {
        String json = """
                {
                  "система": "СКУД",
                  "регламент": [
                    {
                      "номер": 1,
                      "категория_работ": "Обязательные",
                      "тип_инцидента": "Техническое обслуживание устройства контроля доступа",
                      "чек_лист_действий": [
                        "Внешний осмотр общего состояния системных элементов.",
                        "Очистка загрязнений на рабочих поверхностях.",
                        "Проверка работоспособности устройства."
                      ],
                      "периодичность": "Ежемесячно"
                    },
                    {
                      "номер": 3,
                      "категория_работ": "Обязательные",
                      "тип_инцидента": "Сезонное обслуживание системы: 1. замена летней смазки на зимнюю",
                      "чек_лист_действий": [],
                      "периодичность": "Два раза в год"
                    }
                  ]
                }
                """;
        var result = parser.parse("skud.json", new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.systemType()).isEqualTo("СКУД");
        List<PkmOperation> ops = result.operations();
        assertThat(ops).hasSize(2);

        PkmOperation first = ops.get(0);
        assertThat(first.getPosition()).isEqualTo(1);
        assertThat(first.getCategory()).isEqualTo("Обязательные");
        assertThat(first.getOperationName()).isEqualTo("Техническое обслуживание устройства контроля доступа");
        assertThat(first.getPeriodicityPerYear()).isEqualByComparingTo("12");
        // чек-лист → нумерованный состав работ с новой строки
        assertThat(first.getWorkComposition())
                .contains("1. Внешний осмотр")
                .contains("\n3. Проверка работоспособности");

        // операция без чек-листа: состав берётся из части названия после «:»
        PkmOperation second = ops.get(1);
        assertThat(second.getOperationName()).isEqualTo("Сезонное обслуживание системы");
        assertThat(second.getWorkComposition()).contains("замена летней смазки");
        assertThat(second.getPeriodicityPerYear()).isEqualByComparingTo("2");
    }
}
