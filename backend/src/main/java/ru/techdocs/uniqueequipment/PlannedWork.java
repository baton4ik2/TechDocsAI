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
    /** Регламент, заведённый в Midio: решение инженера, а не извлечение ИИ из текста. */
    public static final String SOURCE_MIDIO = "MIDIO";

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

    /** Дословная цитата из паспорта, на которой основана работа и её периодичность. */
    @Column(name = "source_quote", columnDefinition = "text")
    private String sourceQuote;

    /** Страница паспорта с этой цитатой. */
    @Column(name = "source_page")
    private Integer sourcePage;

    /**
     * Найдена ли цитата в тексте паспорта. false — модель её сочинила или
     * пересказала: работу показываем, но помечаем «требует проверки».
     * null — работа добавлена вручную, проверять нечего.
     */
    @Column(name = "quote_verified")
    private Boolean quoteVerified;

    /** Идентификатор работы во внешней системе — чтобы синхронизация не плодила дубли. */
    @Column(name = "external_id")
    private String externalId;

    /**
     * Происхождение во внешней системе: план Midio и изделие, к которому работа
     * там относилась. По нему проверяются спорные привязки.
     */
    @Column(name = "source_note", columnDefinition = "text")
    private String sourceNote;

    /**
     * Обязательная работа или рекомендуемая. null — источник этого не различает
     * (паспорт, ручной ввод), и додумывать за него нельзя.
     */
    @Column(name = "mandatory")
    private Boolean mandatory;

    /** Обоснование для сметы: «Паспорт, с. 14» или «Midio» — то, что видит инженер. */
    @Transient
    public String getSourceLabel() {
        if (SOURCE_MIDIO.equals(source)) return "Midio";
        if (!SOURCE_PASSPORT.equals(source)) return null;
        return sourcePage == null ? "Паспорт" : "Паспорт, с. " + sourcePage;
    }
}
