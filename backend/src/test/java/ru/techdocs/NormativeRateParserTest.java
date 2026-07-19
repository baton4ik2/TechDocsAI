package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.normative.NormativeRate;
import ru.techdocs.normative.NormativeRateParser;
import ru.techdocs.processing.PageText;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NormativeRateParserTest {

    private final NormativeRateParser parser = new NormativeRateParser();

    /** Текст страницы сборника, как его выдаёт PDFBox: наименование + 6 стоимостных колонок. */
    private static final String PAGE = """
            Раздел 3. Техническое обслуживание систем безопасности

            Состав работ:
            1. Внешний осмотр состояния прибора. 2. Проверка работоспособности.
            3. Очистка контактов от пыли.
            Измеритель: 1 прибор

            22-2203-95-1/1 Техническое обслуживание прибора приемно-контрольного типа 6424 502,15 421,30 45,20 12,10 35,65 4,25
            22-2203-96-1/1 Техническое обслуживание извещателя пожарного дымового - 380,50 - - 12,40 3,80
            """;

    @Test
    void extractsTwoRatesWithCostsAssignedRightToLeft() {
        List<NormativeRate> rates = parser.parse(List.of(new PageText(7, PAGE)));

        assertThat(rates).hasSize(2);

        NormativeRate first = rates.get(0);
        assertThat(first.getCode()).isEqualTo("22-2203-95-1/1");
        // дефис внутри слова не должен обрезать наименование и попадать в стоимости
        assertThat(first.getName()).isEqualTo("Техническое обслуживание прибора приемно-контрольного типа 6424");
        assertThat(first.getLaborCost()).isEqualByComparingTo("421.30");    // ЗП
        assertThat(first.getMachineCost()).isEqualByComparingTo("45.20");   // ЭМ
        assertThat(first.getMachineLabor()).isEqualByComparingTo("12.10");  // ЗПМ
        assertThat(first.getMaterialCost()).isEqualByComparingTo("35.65");  // МР
        assertThat(first.getLaborHours()).isEqualByComparingTo("4.25");     // затраты труда
        assertThat(first.getUnit()).isEqualTo("1 прибор");
        assertThat(first.getWorkComposition()).contains("Внешний осмотр").contains("Очистка контактов");
        assertThat(first.getPageNumber()).isEqualTo(7);
    }

    @Test
    void treatsDashAsZeroCost() {
        List<NormativeRate> rates = parser.parse(List.of(new PageText(7, PAGE)));

        NormativeRate second = rates.get(1);
        assertThat(second.getCode()).isEqualTo("22-2203-96-1/1");
        assertThat(second.getName()).isEqualTo("Техническое обслуживание извещателя пожарного дымового");
        assertThat(second.getLaborCost()).isEqualByComparingTo("380.50");
        assertThat(second.getMachineCost()).isEqualByComparingTo(BigDecimal.ZERO);   // прочерк → 0
        assertThat(second.getMachineLabor()).isEqualByComparingTo(BigDecimal.ZERO);  // прочерк → 0
        assertThat(second.getMaterialCost()).isEqualByComparingTo("12.40");
        assertThat(second.getLaborHours()).isEqualByComparingTo("3.80");
    }

    @Test
    void handlesThousandsSeparatorInCost() {
        // колонки: Прямые, ЗП, ЭМ, ЗПМ, МР, затраты труда. ЗП с разделителем тысяч.
        String page = "1-2903-7-3/1 Пусконаладочные работы системы 2 230,80 1 250,50 120,30 40,00 150,20 12,500";
        List<NormativeRate> rates = parser.parse(List.of(new PageText(1, page)));

        assertThat(rates).hasSize(1);
        assertThat(rates.get(0).getLaborCost()).isEqualByComparingTo("1250.50");  // «1 250,50» → 1250.50
        assertThat(rates.get(0).getLaborHours()).isEqualByComparingTo("12.500");
    }

    @Test
    void skipsCodeReferencesWithoutCosts() {
        // ссылка на шифр в тексте (без стоимостных значений) не должна давать расценку
        String page = "Применяется расценка 22-2203-95-1/1 для приборов данного типа.";
        List<NormativeRate> rates = parser.parse(List.of(new PageText(1, page)));

        assertThat(rates).isEmpty();
    }

    @Test
    void returnsEmptyForBlankPages() {
        assertThat(parser.parse(List.of(new PageText(1, "   ")))).isEmpty();
        assertThat(parser.parse(List.of())).isEmpty();
    }
}
