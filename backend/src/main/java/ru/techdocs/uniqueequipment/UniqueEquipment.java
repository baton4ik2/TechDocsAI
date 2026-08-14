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

    /** Ключ модели без системы: наименование|модель|производитель (нормализ.). */
    @Column(name = "equip_key")
    private String equipKey;

    /** Канонический токен инженерной системы (скуд, апс, …) — часть идентичности. */
    @Column(name = "system_type")
    private String systemType;

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

    /** Модель, разобравшая паспорт, — чтобы сравнивать модели между собой. */
    @Column(name = "passport_model")
    private String passportModel;

    /** Как читали паспорт: TEXT (текстовый слой), OCR или VISION (картинка). */
    @Column(name = "passport_mode")
    private String passportMode;

    /** Момент старта разбора — по нему видно, что PROCESSING завис. */
    @Column(name = "passport_started_at")
    private Instant passportStartedAt;

    /** Идентификатор этого же оборудования в Midio (привязка не по названию). */
    @Column(name = "midio_id")
    private String midioId;

    @Column(name = "midio_synced_at")
    private Instant midioSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
