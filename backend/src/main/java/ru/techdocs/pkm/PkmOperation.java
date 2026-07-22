package ru.techdocs.pkm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Регламентная операция ТО: наименование, состав работ, периодичность. */
@Entity
@Table(name = "pkm_operations")
@Getter
@Setter
@NoArgsConstructor
public class PkmOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pkm_id", nullable = false)
    private Long pkmId;

    @Column(name = "system_type")
    private String systemType;

    private Integer position;

    private String category;            // Обязательные / Рекомендуемые

    @Column(name = "operation_name", nullable = false, columnDefinition = "text")
    private String operationName;

    @Column(name = "work_composition", columnDefinition = "text")
    private String workComposition;

    private String periodicity;         // «Ежемесячно», «Два раза в год»

    @Column(name = "periodicity_per_year")
    private BigDecimal periodicityPerYear;   // 12, 4, 2, 1 …
}
