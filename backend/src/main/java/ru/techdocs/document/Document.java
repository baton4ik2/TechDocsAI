package ru.techdocs.document;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
public class Document {

    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_NEEDS_OCR = "NEEDS_OCR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "engineering_system_id")
    private Long engineeringSystemId;

    @Column(name = "document_type_id")
    private Long documentTypeId;

    @Column(nullable = false)
    private String name;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(nullable = false)
    private long size;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(nullable = false)
    private String status = STATUS_UPLOADED;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "actuality_status", nullable = false)
    private String actualityStatus = "ACTUAL";

    private String version;

    @Column(name = "document_date")
    private LocalDate documentDate;

    private String checksum;

    @Column(name = "drive_file_id")
    private String driveFileId;

    @Column(name = "drive_modified_time")
    private String driveModifiedTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
