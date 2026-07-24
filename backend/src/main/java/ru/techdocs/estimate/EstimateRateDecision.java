package ru.techdocs.estimate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Эталонное решение: для уникального оборудования и категории операции — какая
 * расценка и периодичность. Наполняется загрузкой эталона (XLSX) и кнопкой
 * «В эталон». Используется при сборке сметы вместо ИИ (детерминированно, консистентно).
 */
@Entity
@Table(name = "estimate_rate_decisions")
@Getter
@Setter
@NoArgsConstructor
public class EstimateRateDecision {

    public static final String SOURCE_REFERENCE = "REFERENCE";
    public static final String SOURCE_APPROVED = "APPROVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_equipment_id", nullable = false)
    private Long uniqueEquipmentId;

    @Column(name = "operation_key", nullable = false)
    private String operationKey;

    @Column(name = "operation_name", columnDefinition = "text")
    private String operationName;

    @Column(name = "rate_code")
    private String rateCode;

    @Column(name = "rate_name", columnDefinition = "text")
    private String rateName;

    private String periodicity;

    @Column(name = "per_year")
    private BigDecimal perYear;

    private BigDecimal correction;

    @Column(nullable = false)
    private String source = SOURCE_REFERENCE;

    @Column(name = "times_used", nullable = false)
    private int timesUsed = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
