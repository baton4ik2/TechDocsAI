package ru.techdocs.equipment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "equipment")
@Getter
@Setter
@NoArgsConstructor
public class Equipment {

    public static final String STATUS_AUTO = "AUTO_EXTRACTED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "engineering_system_id")
    private Long engineeringSystemId;

    private String manufacturer;

    private String name;

    private String model;

    private String modification;

    @Column(nullable = false)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(nullable = false)
    private String unit = "шт.";

    private String location;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(nullable = false)
    private String status = STATUS_AUTO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
