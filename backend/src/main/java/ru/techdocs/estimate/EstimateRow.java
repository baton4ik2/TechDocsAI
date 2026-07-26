package ru.techdocs.estimate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Строка сметы: снимок оборудования + мероприятие + расценка + количества.
 * Хранятся только ВХОДНЫЕ величины; все денежные колонки вычисляются
 * детерминированно ({@link EstimateCalculator}) и не сохраняются.
 */
@Entity
@Table(name = "estimate_rows")
@Getter
@Setter
@NoArgsConstructor
public class EstimateRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "estimate_id", nullable = false)
    private Long estimateId;

    private Integer position;

    private String section;             // «РАЗДЕЛ А. СКУД»

    @Column(name = "equipment_id")
    private Long equipmentId;

    @Column(name = "equipment_name", columnDefinition = "text")
    private String equipmentName;

    @Column(name = "equipment_type")
    private String equipmentType;

    private String manufacturer;

    @Column(name = "operation_name", columnDefinition = "text")
    private String operationName;       // E

    @Column(name = "rate_code")
    private String rateCode;            // F

    @Column(name = "rate_name", columnDefinition = "text")
    private String rateName;            // G

    private String periodicity;         // H

    @Column(columnDefinition = "text")
    private String justification;       // I

    @Column(name = "ops_per_year")
    private BigDecimal opsPerYear;      // J

    private BigDecimal qty;             // K

    @Column(name = "unit_basis", nullable = false)
    private BigDecimal unitBasis = BigDecimal.ONE;   // M

    @Column(name = "price_zp")
    private BigDecimal priceZp;         // O

    @Column(name = "price_em")
    private BigDecimal priceEm;         // P

    @Column(name = "price_zpm")
    private BigDecimal priceZpm;        // Q

    @Column(name = "price_mr")
    private BigDecimal priceMr;         // R

    @Column(nullable = false)
    private BigDecimal correction = BigDecimal.ONE;  // S

    @Column(name = "labor_hours")
    private BigDecimal laborHours;      // AP

    /** Строку нужно проверить инженеру (ИИ не подобрал расценку или подбор без ИИ). */
    @Column(name = "needs_review", nullable = false)
    private boolean needsReview = false;

    /** Источник расценки: LEARNED / AI / AI_FAILED / CATALOG / MANUAL. */
    @Column(name = "match_source")
    private String matchSource;
}
