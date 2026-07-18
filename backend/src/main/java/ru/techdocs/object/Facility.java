package ru.techdocs.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "facilities")
@Getter
@Setter
@NoArgsConstructor
public class Facility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId = 1L;

    @Column(nullable = false)
    private String name;

    private String address;

    private String description;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "drive_folder_id")
    private String driveFolderId;

    @Column(name = "drive_synced_at")
    private Instant driveSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
