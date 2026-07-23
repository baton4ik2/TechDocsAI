package ru.techdocs.uniqueequipment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Плановая работа по оборудованию: осмотр / ТО / контроль функционирования и т.п. */
@Entity
@Table(name = "planned_works")
@Getter
@Setter
@NoArgsConstructor
public class PlannedWork {

    public static final String SOURCE_PASSPORT = "PASSPORT";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_equipment_id", nullable = false)
    private Long uniqueEquipmentId;

    private Integer position;

    @Column(name = "work_type")
    private String workType;            // осмотр, ТО, контроль функционирования …

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "work_composition", columnDefinition = "text")
    private String workComposition;

    private String periodicity;

    @Column(name = "periodicity_per_year")
    private BigDecimal periodicityPerYear;

    @Column(nullable = false)
    private String source = SOURCE_MANUAL;
}
