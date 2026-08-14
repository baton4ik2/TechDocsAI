package ru.techdocs.estimate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Смета планового обслуживания объекта. Коэффициенты хранятся снимком в самой
 * смете, чтобы изменение ставок не «сдвигало» ранее составленные сметы.
 */
@Entity
@Table(name = "estimates")
@Getter
@Setter
@NoArgsConstructor
public class Estimate {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_READY = "READY";

    // значения по умолчанию — как в эталонном расчёте («Данные для расчёта»)
    public static final BigDecimal DEFAULT_NR_ZP = new BigDecimal("0.7");
    public static final BigDecimal DEFAULT_NP_ZP = new BigDecimal("0.1");
    public static final BigDecimal DEFAULT_NR_EM = new BigDecimal("0.78");
    public static final BigDecimal DEFAULT_NP_EM = new BigDecimal("0.3");
    public static final BigDecimal DEFAULT_VAT = new BigDecimal("0.22");
    public static final BigDecimal DEFAULT_RT = new BigDecimal("1.998");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "system_id")
    private Long systemId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status = STATUS_DRAFT;

    @Column(name = "nr_zp", nullable = false)
    private BigDecimal nrZp = DEFAULT_NR_ZP;

    @Column(name = "np_zp", nullable = false)
    private BigDecimal npZp = DEFAULT_NP_ZP;

    @Column(name = "nr_em", nullable = false)
    private BigDecimal nrEm = DEFAULT_NR_EM;

    @Column(name = "np_em", nullable = false)
    private BigDecimal npEm = DEFAULT_NP_EM;

    @Column(nullable = false)
    private BigDecimal vat = DEFAULT_VAT;

    @Column(name = "rt_coefficient", nullable = false)
    private BigDecimal rtCoefficient = DEFAULT_RT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
