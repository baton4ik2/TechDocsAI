package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.estimate.Estimate;
import ru.techdocs.estimate.EstimateCalculator;
import ru.techdocs.estimate.EstimateRow;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class EstimateCalculatorTest {

    private final EstimateCalculator calc = new EstimateCalculator();

    private EstimateCalculator.Coefficients defaults() {
        return EstimateCalculator.Coefficients.of(new Estimate());
    }

    private EstimateRow row(String qty, String ops, String unit, String s,
                            String zp, String em, String zpm, String mr, String laborHours) {
        EstimateRow r = new EstimateRow();
        r.setQty(bd(qty));
        r.setOpsPerYear(bd(ops));
        r.setUnitBasis(bd(unit));
        r.setCorrection(bd(s));
        r.setPriceZp(bd(zp));
        r.setPriceEm(bd(em));
        r.setPriceZpm(bd(zpm));
        r.setPriceMr(bd(mr));
        r.setLaborHours(bd(laborHours));
        return r;
    }

    private BigDecimal bd(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private BigDecimal r2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Эталонная строка №1 из примера (сервер СКУД): совпадение до копейки. */
    @Test
    void matchesReferenceRowExactly() {
        // K=1, J=12, M=1, S=1, O=362.26, P=0, Q=0, R=0.15, AP=0.52
        var res = calc.compute(
                row("1", "12", "1", "1", "362.26", "0", "0", "0.15", "0.52"),
                defaults());

        assertThat(res.performedPerYear()).isEqualByComparingTo("12");   // L
        assertThat(res.totalUnits()).isEqualByComparingTo("12");         // N
        assertThat(res.zp()).isEqualByComparingTo("4347.12");            // T
        assertThat(res.em()).isEqualByComparingTo("0");
        assertThat(res.mr()).isEqualByComparingTo("1.8");                // W
        assertThat(res.nr()).isEqualByComparingTo("3042.984");           // X
        assertThat(res.np()).isEqualByComparingTo("434.712");            // Y
        assertThat(res.totalNoVat()).isEqualByComparingTo("7826.616");   // Z
        assertThat(res.vat()).isEqualByComparingTo("1721.86");           // AA (округление)
        assertThat(res.totalWithVat()).isEqualByComparingTo("9548.476"); // AB

        // блок РТ (сверяем до копеек)
        assertThat(r2(res.zpRt())).isEqualByComparingTo("2175.74");      // AG
        assertThat(res.emRt()).isEqualByComparingTo("0");
        assertThat(r2(res.totalNoVatRt())).isEqualByComparingTo("3918.12"); // AM
        assertThat(res.vatRt()).isEqualByComparingTo("861.99");          // AN
        assertThat(r2(res.totalWithVatRt())).isEqualByComparingTo("4780.11"); // AO

        assertThat(res.laborHoursTotal()).isEqualByComparingTo("6.24");  // AQ
    }

    /** Прочерки (нулевые колонки) и поправочный коэффициент S. */
    @Test
    void appliesCorrectionAndHandlesZeros() {
        // K=4, J=12, M=1, S=0.75, только ЗП=350.16, остальное 0
        var res = calc.compute(
                row("4", "12", "1", "0.75", "350.16", "0", "0", "0", "0"),
                defaults());

        // T = 350.16 * (48/1) * 0.75 = 350.16 * 48 * 0.75 = 12605.76
        assertThat(res.zp()).isEqualByComparingTo("12605.76");
        assertThat(res.em()).isEqualByComparingTo("0");
        assertThat(res.mr()).isEqualByComparingTo("0");
        // НР = T*0.7 ; НП = T*0.1
        assertThat(res.nr()).isEqualByComparingTo("8824.032");
        assertThat(res.np()).isEqualByComparingTo("1260.576");
    }

    /** Единица измерения расценки (M): N = выполнений / объём. */
    @Test
    void dividesByUnitBasis() {
        // K=38, J=1, M=10 → N = 38/10 = 3.8; ЗП=194.48 → T = 194.48*3.8 = 739.024
        var res = calc.compute(
                row("38", "1", "10", "1", "194.48", "0", "0", "0", "0"),
                defaults());
        assertThat(res.totalUnits()).isEqualByComparingTo("3.8");
        assertThat(res.zp()).isEqualByComparingTo("739.024");
    }

    /** Строка-прочерк «расценка не требуется»: нет расценки и операций → итоги 0. */
    @Test
    void zeroWhenNoRateOrOps() {
        var res = calc.compute(
                row("1", "0", "1", "1", "0", "0", "0", "0", "0"),
                defaults());
        assertThat(res.totalNoVat()).isEqualByComparingTo("0");
        assertThat(res.totalWithVat()).isEqualByComparingTo("0");
        assertThat(res.vat()).isEqualByComparingTo("0.00");
    }

    /** Эталонная строка ИБП (кол-во 2): совпадение блоков Москва и РТ до копейки. */
    @Test
    void matchesReferenceIbpRowBothBlocks() {
        // O=2116.73, P(ЭМ)=1.18, Q(ЗПМ)=0.01, R=0, K=2, J=2, M=1, S=1
        var res = calc.compute(
                row("2", "2", "1", "1", "2116.73", "1.18", "0.01", "0", "2.62"),
                defaults());

        // НР и НП в обоих блоках начисляются на ФОТ (ЗП + ЗПМ), не на полный ЭМ
        assertThat(r2(res.totalWithVat())).isEqualByComparingTo("18599.17");   // AB
        assertThat(r2(res.totalNoVatRt())).isEqualByComparingTo("7632.58");    // AM
        assertThat(res.vatRt()).isEqualByComparingTo("1679.17");               // AN
        assertThat(r2(res.totalWithVatRt())).isEqualByComparingTo("9311.75");  // AO
    }

    /**
     * НР считается от ЗПМ, а не от полного ЭМ — как в эталоне АПС.
     * Строка эталона: ЗП=71302.09, ЭМ=7533.55, ЗПМ=4539.27 → НР=53452.09
     * (от ЭМ было бы 55787.63 — расхождение больше 2 тыс. ₽ на строке).
     */
    @Test
    void overheadsUseMachineOperatorsWagesNotFullMachineCost() {
        // подбираем цены так, чтобы получить эталонные ЗП/ЭМ/ЗПМ при N=1
        EstimateRow r = row("1", "1", "1", "1", "71302.09", "7533.55", "4539.27", "0", "0");
        var res = calc.compute(r, defaults());

        assertThat(res.zp()).isEqualByComparingTo("71302.09");
        assertThat(res.em()).isEqualByComparingTo("7533.55");
        assertThat(res.zpm()).isEqualByComparingTo("4539.27");
        // X = 71302.09·0.7 + 4539.27·0.78 = 49911.463 + 3540.6306
        assertThat(r2(res.nr())).isEqualByComparingTo("53452.09");
        // Y = 71302.09·0.1 + 4539.27·0.3 = 7130.209 + 1361.781
        assertThat(r2(res.np())).isEqualByComparingTo("8491.99");
    }

    /**
     * Трудозатраты за год = всего ед. измерения × чел-ч на единицу. AP нормирован
     * на измеритель расценки, поэтому у «10 шт.» брать выполнения нельзя — завысит вдесятеро.
     */
    @Test
    void labourHoursUseTotalUnitsNotPerformances() {
        // K=38, J=2, M=10 → L=76, N=7.6; AP=0.5 → AQ = 7.6·0.5 = 3.8 (а не 76·0.5)
        var res = calc.compute(
                row("38", "2", "10", "1", "100", "0", "0", "0", "0.5"),
                defaults());
        assertThat(res.performedPerYear()).isEqualByComparingTo("76");
        assertThat(res.totalUnits()).isEqualByComparingTo("7.6");
        assertThat(res.laborHoursTotal()).isEqualByComparingTo("3.8");
    }

    /** МР в блоке РТ — без поправочного коэффициента (как и в блоке СН-2012). */
    @Test
    void materialsIgnoreCorrectionInBothBlocks() {
        // R=10, K=2, J=3, M=1 → N=6, S=0.75: МР = 10·6 = 60 в обоих блоках
        var res = calc.compute(
                row("2", "3", "1", "0.75", "0", "0", "0", "10", "0"),
                defaults());
        assertThat(res.mr()).isEqualByComparingTo("60");
        assertThat(res.mrRt()).isEqualByComparingTo("60");
    }

    /** ЭМ с ЗПМ: коэффициент РТ применяется только к ЗПМ внутри ЭМ. */
    @Test
    void rtCoefficientAffectsOnlyLabourInMachineCost() {
        // ЭМ=100 (в т.ч. ЗПМ=40), K=1,J=1,M=1,S=1
        var res = calc.compute(
                row("1", "1", "1", "1", "0", "100", "40", "0", "0"),
                defaults());
        // AD = P - Q + Q/rt = 100 - 40 + 40/1.998 = 60 + 20.02002 = 80.02002
        assertThat(r2(res.emRt())).isEqualByComparingTo("80.02");
        // МР-часть РТ не трогается, ЭМ без ЗПМ (60) остаётся как есть
    }
}
