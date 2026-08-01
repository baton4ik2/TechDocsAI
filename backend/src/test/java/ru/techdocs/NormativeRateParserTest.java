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
    void findsCompositionAndUnitOnPreviousPage() {
        // как в реальном сборнике: таблица с составом работ и измерителем — на одной
        // странице, а сами расценки — на следующей
        String heading = """
                Таблица 22-2203-128. Техническое обслуживание извещателя пожарного дымового
                Состав работ:
                1. Сообщение диспетчеру о начале работ. 2. Внешний осмотр корпуса.
                3. Отключение прибора от сети. 8. Запись в журнале результатов работ.
                Измеритель: шт.
                54
                """;
        String tablePage = """
                Шифр Наименование работ Прямые затраты ЗП ЭМ ЗПМ МР Затраты труда
                22-2203-128-1/1 Техническое обслуживание извещателя пожарного дымового ИП 212-41М "ДИП-41М" - полугодовое 189,73 139,33 - - 50,40 0,20
                """;
        List<NormativeRate> rates = parser.parse(List.of(
                new PageText(54, heading), new PageText(55, tablePage)));

        assertThat(rates).hasSize(1);
        NormativeRate r = rates.get(0);
        assertThat(r.getCode()).isEqualTo("22-2203-128-1/1");
        assertThat(r.getPageNumber()).isEqualTo(55);
        // суммы: ЗП 139,33; ЭМ и ЗПМ прочерк → 0; МР 50,40; труд 0,20
        assertThat(r.getLaborCost()).isEqualByComparingTo("139.33");
        assertThat(r.getMachineCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.getMachineLabor()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.getMaterialCost()).isEqualByComparingTo("50.40");
        assertThat(r.getLaborHours()).isEqualByComparingTo("0.20");
        assertThat(r.getName()).contains("ДИП-41М").contains("полугодовое");
        // состав и измеритель подтянулись с предыдущей страницы
        assertThat(r.getUnit()).isEqualTo("шт.");
        assertThat(r.getWorkComposition())
                .contains("Сообщение диспетчеру")
                .contains("Запись в журнале")
                .contains("\n2.");   // пункты состава — с новой строки
    }

    @Test
    void takesContiguousColumnRunIgnoringTrailingNumbers() {
        // реальный случай: расценка последняя на странице, дальше — заголовок
        // следующей таблицы с числами. Берём сплошной прогон из 6 колонок, а не
        // «последние 6» (иначе ЗП/ЭМ съезжают, а наименование глотает Прямые/ЗП).
        String page = "21-2203-41-1/1 Техническое обслуживание источника бесперебойного питания "
                + "(ИБП) типа APC Smart-UPS SRT 6 кВА стоечного исполнения - полугодовое "
                + "2117,91 2116,73 1,18 0,01 - 2,62\n"
                + "Таблица 21-2203-42. Следующая расценка 999,99 888,88";
        List<NormativeRate> rates = parser.parse(List.of(new PageText(60, page)));

        assertThat(rates).hasSize(1);
        NormativeRate r = rates.get(0);
        assertThat(r.getCode()).isEqualTo("21-2203-41-1/1");
        assertThat(r.getName()).contains("ИБП").contains("полугодовое").doesNotContain("2117,91");
        assertThat(r.getLaborCost()).isEqualByComparingTo("2116.73");   // ЗП
        assertThat(r.getMachineCost()).isEqualByComparingTo("1.18");    // ЭМ
        assertThat(r.getMachineLabor()).isEqualByComparingTo("0.01");   // ЗПМ
        assertThat(r.getMaterialCost()).isEqualByComparingTo(BigDecimal.ZERO); // МР (прочерк)
        assertThat(r.getLaborHours()).isEqualByComparingTo("2.62");     // затраты труда
    }

    @Test
    void skipsMaterialConsumptionRows() {
        // строка из ведомости расхода материалов: шифр расценки + код материала
        // как «наименование» («21.1-20-1 Бязь»). Настоящей расценкой не считается.
        String page = "22-2203-87-1/1 21.1-20-1 Бязь м2 - - 0,05 0,05 0,26";
        List<NormativeRate> rates = parser.parse(List.of(new PageText(162, page)));
        assertThat(rates).isEmpty();
    }

    /**
     * В узкой колонке сборника длинные шифры переносятся на две строки
     * («22-2203-104-» / «11/1»). Такая расценка обязана попасть в каталог: иначе в
     * смете она остаётся без цен и состава работ (случай С2000-СП4).
     */
    @Test
    void readsRateCodeWrappedAcrossLines() {
        String page = """
                Состав работ:
                1. Внешний осмотр корпуса прибора. 2. Проверка работоспособности.
                Измеритель: 1 шт.

                22-2203-104-
                11/1 Техническое обслуживание приборов системы охранно-пожарной сигнализации на базе
                оборудования С2000, блок сигнально-пусковой С2000-СП4 - годовое 248,09 181,13 - - 66,96 0,26
                """;
        List<NormativeRate> rates = parser.parse(List.of(new PageText(46, page)));

        assertThat(rates).hasSize(1);
        NormativeRate r = rates.get(0);
        assertThat(r.getCode()).isEqualTo("22-2203-104-11/1");   // перенос убран
        assertThat(r.getLaborCost()).isEqualByComparingTo("181.13");
        assertThat(r.getMaterialCost()).isEqualByComparingTo("66.96");
        assertThat(r.getLaborHours()).isEqualByComparingTo("0.26");
        assertThat(r.getWorkComposition()).contains("Внешний осмотр корпуса прибора");
    }

    /**
     * Колонки расценки — прогон из 6 значений. Если раньше него в блок попал короткий
     * набор чисел из соседнего текста, брать нужно настоящий: иначе числа
     * раскладываются со сдвигом и расценка остаётся без ЗП (случай 22-2203-117-1/1).
     */
    @Test
    void picksFullSixColumnRunEvenWhenShorterRunComesFirst() {
        String page = """
                Состав работ:
                1. Проверка работоспособности систем противопожарной защиты.
                Измеритель: 1000 м2

                22-2203-117-1/1 Проверка работоспособности систем противопожарной
                защиты 0,1 1,1 - - зданий и сооружений
                502,15 463,84 0,44 0,01 - 0,48
                """;
        List<NormativeRate> rates = parser.parse(List.of(new PageText(60, page)));

        assertThat(rates).hasSize(1);
        NormativeRate r = rates.get(0);
        assertThat(r.getLaborCost()).isEqualByComparingTo("463.84");    // ЗП
        assertThat(r.getMachineCost()).isEqualByComparingTo("0.44");    // ЭМ
        assertThat(r.getMachineLabor()).isEqualByComparingTo("0.01");   // ЗПМ
        assertThat(r.getMaterialCost()).isEqualByComparingTo("0");      // МР (прочерк)
        assertThat(r.getLaborHours()).isEqualByComparingTo("0.48");
    }

    /**
     * Прогон без заработной платы — не колонки расценки. Лучше оставить цены пустыми,
     * чем разложить чужие числа: пустое видно, а правдоподобный мусор — нет.
     */
    @Test
    void doesNotGuessPricesFromTooShortRun() {
        String page = "22-2203-118-3/1 Оборудование стойки оповещения, каждая последующая линия 0,09 0,24";
        List<NormativeRate> rates = parser.parse(List.of(new PageText(61, page)));

        assertThat(rates).hasSize(1);
        NormativeRate r = rates.get(0);
        assertThat(r.getCode()).isEqualTo("22-2203-118-3/1");
        assertThat(r.getLaborCost()).isNull();
        assertThat(r.getMachineLabor()).isNull();
    }

    @Test
    void returnsEmptyForBlankPages() {
        assertThat(parser.parse(List.of(new PageText(1, "   ")))).isEmpty();
        assertThat(parser.parse(List.of())).isEmpty();
    }
}
