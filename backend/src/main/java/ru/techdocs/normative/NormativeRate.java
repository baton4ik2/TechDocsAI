package ru.techdocs.normative;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Расценка СН-2012: шифр, наименование, состав работ и стоимостные показатели. */
@Entity
@Table(name = "normative_rates")
@Getter
@Setter
@NoArgsConstructor
public class NormativeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sourcebook_id", nullable = false)
    private Long sourcebookId;

    @Column(nullable = false)
    private String code;                // 22-2203-95-1/1

    @Column(nullable = false, columnDefinition = "text")
    private String name;                // Наименование работ

    private String unit;                // Измеритель (1 шт., 10 шт.)

    @Column(name = "work_composition", columnDefinition = "text")
    private String workComposition;     // Состав работ

    @Column(name = "labor_cost")
    private BigDecimal laborCost;       // ЗП, руб

    @Column(name = "machine_cost")
    private BigDecimal machineCost;     // ЭМ всего, руб

    @Column(name = "machine_labor")
    private BigDecimal machineLabor;    // в т.ч. ЗПМ, руб

    @Column(name = "material_cost")
    private BigDecimal materialCost;    // МР, руб

    @Column(name = "labor_hours")
    private BigDecimal laborHours;      // затраты труда, чел-ч

    @Column(name = "page_number")
    private Integer pageNumber;
}
