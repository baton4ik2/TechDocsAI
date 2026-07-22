package ru.techdocs.estimate;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Детерминированный расчёт денежных колонок строки сметы. Никакого ИИ — только
 * арифметика по формулам эталонного расчёта. Формулы (левый блок — уровень СН-2012,
 * правый — перевод в уровень РТ):
 * <pre>
 *   L = K·J                      N = L/M
 *   T(ЗП)=O·N·S  U(ЭМ)=P·N·S  V(ЗПМ)=Q·N·S  W(МР)=R·N
 *   X(НР)=T·нрЗП + U·нрЭМ         Y(НП)=T·нпЗП + V·нпЭМ
 *   Z = T+U+W+X+Y                НДС=round(Z·ставка,2)   Итого=Z+НДС
 *   РТ: ЗП/ЗПМ делятся на коэффициент РТ, ЭМ = ЭМ−ЗПМ+ЗПМ_рт, МР без изменений
 * </pre>
 * Округляется только НДС (до копеек), как в эталоне; остальное — полная точность.
 */
@Service
public class EstimateCalculator {

    private static final int DIV_SCALE = 10;

    /** Снимок коэффициентов сметы. */
    public record Coefficients(BigDecimal nrZp, BigDecimal npZp, BigDecimal nrEm,
                               BigDecimal npEm, BigDecimal vat, BigDecimal rt) {
        public static Coefficients of(Estimate e) {
            return new Coefficients(e.getNrZp(), e.getNpZp(), e.getNrEm(), e.getNpEm(),
                    e.getVat(), e.getRtCoefficient());
        }
    }

    /** Все вычисленные колонки строки (оба блока + справочные трудозатраты). */
    public record RowResult(
            BigDecimal performedPerYear,  // L
            BigDecimal totalUnits,        // N
            // блок СН-2012
            BigDecimal zp, BigDecimal em, BigDecimal zpm, BigDecimal mr,
            BigDecimal nr, BigDecimal np, BigDecimal totalNoVat, BigDecimal vat, BigDecimal totalWithVat,
            // блок РТ
            BigDecimal zpRt, BigDecimal emRt, BigDecimal zpmRt, BigDecimal mrRt,
            BigDecimal nrRt, BigDecimal npRt, BigDecimal totalNoVatRt, BigDecimal vatRt, BigDecimal totalWithVatRt,
            // справочно
            BigDecimal laborHoursTotal) {
    }

    public RowResult compute(EstimateRow row, Coefficients c) {
        BigDecimal k = nz(row.getQty());
        BigDecimal j = nz(row.getOpsPerYear());
        BigDecimal m = row.getUnitBasis() == null ? BigDecimal.ONE : row.getUnitBasis();
        BigDecimal s = row.getCorrection() == null ? BigDecimal.ONE : row.getCorrection();
        BigDecimal o = nz(row.getPriceZp());
        BigDecimal p = nz(row.getPriceEm());
        BigDecimal q = nz(row.getPriceZpm());
        BigDecimal r = nz(row.getPriceMr());

        BigDecimal performed = k.multiply(j);                                   // L
        BigDecimal n = m.signum() == 0 ? BigDecimal.ZERO : div(performed, m);   // N

        // --- блок СН-2012 ---
        BigDecimal zp = o.multiply(n).multiply(s);      // T
        BigDecimal em = p.multiply(n).multiply(s);      // U
        BigDecimal zpm = q.multiply(n).multiply(s);     // V
        BigDecimal mr = r.multiply(n);                  // W (без S)
        BigDecimal nr = zp.multiply(c.nrZp()).add(em.multiply(c.nrEm()));   // X
        BigDecimal np = zp.multiply(c.npZp()).add(zpm.multiply(c.npEm()));  // Y
        BigDecimal noVat = zp.add(em).add(mr).add(nr).add(np);              // Z
        BigDecimal vat = round2(noVat.multiply(c.vat()));                   // AA
        BigDecimal withVat = noVat.add(vat);                               // AB

        // --- блок РТ (коэффициент только к ЗП и ЗПМ) ---
        BigDecimal rt = c.rt() == null || c.rt().signum() == 0 ? BigDecimal.ONE : c.rt();
        BigDecimal oRt = div(o, rt);            // AC
        BigDecimal qRt = div(q, rt);            // AE
        BigDecimal pRt = p.subtract(q).add(qRt); // AD = ЭМ − ЗПМ + ЗПМ_рт
        BigDecimal zpRt = oRt.multiply(n).multiply(s);   // AG
        BigDecimal emRt = pRt.multiply(n).multiply(s);   // AH
        BigDecimal zpmRt = qRt.multiply(n).multiply(s);  // AI
        BigDecimal mrRt = r.multiply(n);                 // AJ
        BigDecimal nrRt = zpRt.multiply(c.nrZp()).add(emRt.multiply(c.nrEm()));  // AK
        BigDecimal npRt = zpRt.multiply(c.npZp()).add(zpmRt.multiply(c.npEm())); // AL
        BigDecimal noVatRt = zpRt.add(emRt).add(mrRt).add(nrRt).add(npRt);       // AM
        BigDecimal vatRt = round2(noVatRt.multiply(c.vat()));                    // AN
        BigDecimal withVatRt = noVatRt.add(vatRt);                               // AO

        BigDecimal laborTotal = n.multiply(nz(row.getLaborHours()));             // AQ

        return new RowResult(performed, n,
                zp, em, zpm, mr, nr, np, noVat, vat, withVat,
                zpRt, emRt, zpmRt, mrRt, nrRt, npRt, noVatRt, vatRt, withVatRt,
                laborTotal);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal div(BigDecimal a, BigDecimal b) {
        return a.divide(b, DIV_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
