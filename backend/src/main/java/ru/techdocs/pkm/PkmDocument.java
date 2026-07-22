package ru.techdocs.pkm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Регламент (перечень контрольных мероприятий) по типу инженерной системы. */
@Entity
@Table(name = "pkm_documents")
@Getter
@Setter
@NoArgsConstructor
public class PkmDocument {

    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "system_type")
    private String systemType;          // СКУД, АПС, СОУЭ …

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "storage_path")
    private String storagePath;

    @Column(name = "operation_count", nullable = false)
    private int operationCount = 0;

    @Column(nullable = false)
    private String status = STATUS_UPLOADED;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
