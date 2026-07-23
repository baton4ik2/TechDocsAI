package ru.techdocs.uniqueequipment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Уникальное оборудование — одна запись на модель, общая для всех объектов.
 * К нему привязываются паспорт и плановые работы; оборудование объектов
 * линкуется по нормализованному ключу (наименование|модель|производитель).
 */
@Entity
@Table(name = "unique_equipment")
@Getter
@Setter
@NoArgsConstructor
public class UniqueEquipment {

    public static final String PASSPORT_UPLOADED = "UPLOADED";
    public static final String PASSPORT_PROCESSING = "PROCESSING";
    public static final String PASSPORT_READY = "READY";
    public static final String PASSPORT_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "norm_key", nullable = false, unique = true)
    private String normKey;

    private String name;
    private String model;
    private String manufacturer;

    @Column(name = "passport_filename")
    private String passportFilename;

    @Column(name = "passport_storage_path")
    private String passportStoragePath;

    @Column(name = "passport_status")
    private String passportStatus;

    @Column(name = "passport_error")
    private String passportError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
