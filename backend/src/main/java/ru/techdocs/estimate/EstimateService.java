package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.NotFoundException;
import ru.techdocs.common.Periodicity;
import ru.techdocs.normative.NormativeRate;
import ru.techdocs.normative.NormativeRateRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EstimateService {

    private final EstimateRepository estimateRepository;
    private final EstimateRowRepository rowRepository;
    private final NormativeRateRepository rateRepository;
    private final EstimateCalculator calculator;

    // ---- запросы на изменение ----

    public record EstimateInput(String name, Long systemId,
                                BigDecimal nrZp, BigDecimal npZp, BigDecimal nrEm,
                                BigDecimal npEm, BigDecimal vat, BigDecimal rtCoefficient,
                                String status) {}

    public record RowInput(String section, Long equipmentId, String equipmentName,
                           String equipmentType, String manufacturer, String operationName,
                           String rateCode, String rateName, String periodicity, String justification,
                           BigDecimal opsPerYear, BigDecimal qty, BigDecimal unitBasis,
                           BigDecimal priceZp, BigDecimal priceEm, BigDecimal priceZpm, BigDecimal priceMr,
                           BigDecimal correction, BigDecimal laborHours) {}

    // ---- представление ----

    public record RowView(EstimateRow row, EstimateCalculator.RowResult calc) {}

    public record Totals(BigDecimal totalNoVat, BigDecimal vat, BigDecimal totalWithVat,
                         BigDecimal totalNoVatRt, BigDecimal vatRt, BigDecimal totalWithVatRt,
                         BigDecimal laborHoursTotal) {}

    public record EstimateView(Estimate estimate, List<RowView> rows, Totals totals) {}

    // ---- смета ----

    /** Создание сметы для объекта (facilityId обязателен). */
    public Estimate create(Long facilityId, EstimateInput input) {
        if (facilityId == null) throw new BadRequestException("Не указан объект.");
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new BadRequestException("Укажите название сметы.");
        }
        Estimate e = new Estimate();
        e.setFacilityId(facilityId);
        e.setName(input.name().strip());
        e.setSystemId(input.systemId());
        applyCoefficients(e, input);
        return estimateRepository.save(e);
    }

    public List<Estimate> list(Long facilityId) {
        return facilityId == null
                ? estimateRepository.findAllByOrderByCreatedAtDesc()
                : estimateRepository.findByFacilityIdOrderByCreatedAtDesc(facilityId);
    }

    public Estimate get(Long id) {
        return estimateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Смета не найдена"));
    }

    public EstimateView view(Long id) {
        Estimate estimate = get(id);
        var coeffs = EstimateCalculator.Coefficients.of(estimate);
        List<RowView> views = new ArrayList<>();
        BigDecimal noVat = BigDecimal.ZERO, vat = BigDecimal.ZERO, withVat = BigDecimal.ZERO;
        BigDecimal noVatRt = BigDecimal.ZERO, vatRt = BigDecimal.ZERO, withVatRt = BigDecimal.ZERO;
        BigDecimal labor = BigDecimal.ZERO;
        for (EstimateRow row : rowRepository.findByEstimateIdOrderByPosition(id)) {
            EstimateCalculator.RowResult calc = calculator.compute(row, coeffs);
            views.add(new RowView(row, calc));
            noVat = noVat.add(calc.totalNoVat());
            vat = vat.add(calc.vat());
            withVat = withVat.add(calc.totalWithVat());
            noVatRt = noVatRt.add(calc.totalNoVatRt());
            vatRt = vatRt.add(calc.vatRt());
            withVatRt = withVatRt.add(calc.totalWithVatRt());
            labor = labor.add(calc.laborHoursTotal());
        }
        Totals totals = new Totals(noVat, vat, withVat, noVatRt, vatRt, withVatRt, labor);
        return new EstimateView(estimate, views, totals);
    }

    public Estimate update(Long id, EstimateInput input) {
        Estimate e = get(id);
        if (input.name() != null && !input.name().isBlank()) e.setName(input.name().strip());
        if (input.systemId() != null) e.setSystemId(input.systemId());
        if (input.status() != null) e.setStatus(input.status());
        applyCoefficients(e, input);
        e.setUpdatedAt(Instant.now());
        return estimateRepository.save(e);
    }

    public void delete(Long id) {
        Estimate e = get(id);
        estimateRepository.delete(e);
    }

    private void applyCoefficients(Estimate e, EstimateInput in) {
        if (in.nrZp() != null) e.setNrZp(in.nrZp());
        if (in.npZp() != null) e.setNpZp(in.npZp());
        if (in.nrEm() != null) e.setNrEm(in.nrEm());
        if (in.npEm() != null) e.setNpEm(in.npEm());
        if (in.vat() != null) e.setVat(in.vat());
        if (in.rtCoefficient() != null) e.setRtCoefficient(in.rtCoefficient());
    }

    // ---- строки ----

    public EstimateRow addRow(Long estimateId, RowInput input) {
        get(estimateId);
        EstimateRow row = new EstimateRow();
        row.setEstimateId(estimateId);
        row.setPosition((int) (rowRepository.countByEstimateId(estimateId) + 1));
        row.setUnitBasis(BigDecimal.ONE);
        row.setCorrection(BigDecimal.ONE);
        applyRow(row, input, true);
        return rowRepository.save(row);
    }

    public EstimateRow updateRow(Long rowId, RowInput input) {
        EstimateRow row = rowRepository.findById(rowId)
                .orElseThrow(() -> new NotFoundException("Строка сметы не найдена"));
        applyRow(row, input, false);
        return rowRepository.save(row);
    }

    public void deleteRow(Long rowId) {
        if (!rowRepository.existsById(rowId)) throw new NotFoundException("Строка сметы не найдена");
        rowRepository.deleteById(rowId);
    }

    /**
     * Применяет входные поля к строке. Если задан шифр расценки — подтягивает из
     * каталога наименование, цены (ЗП/ЭМ/ЗПМ/МР), измеритель и трудозатраты
     * (кроме полей, явно переданных вручную). Периодичность → операций в год.
     */
    private void applyRow(EstimateRow row, RowInput in, boolean creating) {
        if (in == null) return;
        if (in.section() != null) row.setSection(blank(in.section()));
        if (in.equipmentId() != null) row.setEquipmentId(in.equipmentId());
        if (in.equipmentName() != null) row.setEquipmentName(blank(in.equipmentName()));
        if (in.equipmentType() != null) row.setEquipmentType(blank(in.equipmentType()));
        if (in.manufacturer() != null) row.setManufacturer(blank(in.manufacturer()));
        if (in.operationName() != null) row.setOperationName(blank(in.operationName()));
        if (in.periodicity() != null) row.setPeriodicity(blank(in.periodicity()));
        if (in.justification() != null) row.setJustification(blank(in.justification()));
        if (in.qty() != null) row.setQty(in.qty());
        if (in.correction() != null) row.setCorrection(in.correction());

        boolean rateChanged = in.rateCode() != null && !in.rateCode().equals(row.getRateCode());
        if (in.rateCode() != null) row.setRateCode(blank(in.rateCode()));

        // автозаполнение из каталога по шифру (при создании или смене шифра)
        if (row.getRateCode() != null && (creating || rateChanged)) {
            fillFromCatalog(row);
        }

        // явные ручные значения перекрывают автозаполнение
        if (in.rateName() != null) row.setRateName(blank(in.rateName()));
        if (in.unitBasis() != null) row.setUnitBasis(in.unitBasis());
        if (in.priceZp() != null) row.setPriceZp(in.priceZp());
        if (in.priceEm() != null) row.setPriceEm(in.priceEm());
        if (in.priceZpm() != null) row.setPriceZpm(in.priceZpm());
        if (in.priceMr() != null) row.setPriceMr(in.priceMr());
        if (in.laborHours() != null) row.setLaborHours(in.laborHours());

        // периодичность → операций в год (если явно не задано)
        if (in.opsPerYear() != null) {
            row.setOpsPerYear(in.opsPerYear());
        } else if (in.periodicity() != null) {
            BigDecimal perYear = Periodicity.perYear(in.periodicity());
            if (perYear != null) row.setOpsPerYear(perYear);
        }
    }

    private void fillFromCatalog(EstimateRow row) {
        NormativeRate rate = rateRepository.findFirstByCodeOrderById(row.getRateCode()).orElse(null);
        if (rate == null) return;
        row.setRateName(rate.getName());
        row.setPriceZp(rate.getLaborCost());
        row.setPriceEm(rate.getMachineCost());
        row.setPriceZpm(rate.getMachineLabor());
        row.setPriceMr(rate.getMaterialCost());
        row.setLaborHours(rate.getLaborHours());
        row.setUnitBasis(RateUnits.basis(rate.getUnit()));
    }

    private String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
