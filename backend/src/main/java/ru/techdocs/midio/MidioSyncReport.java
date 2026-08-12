package ru.techdocs.midio;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Последний отчёт синхронизации с Midio. Живёт в базе, а не в состоянии
 * страницы: уход со страницы не теряет список ручных подтверждений.
 * Одна запись — новый прогон заменяет предыдущий.
 */
@Entity
@Table(name = "midio_sync_reports")
@Getter
@Setter
@NoArgsConstructor
public class MidioSyncReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SyncResult в JSON — форма отчёта меняется вместе с интеграцией. */
    @Column(columnDefinition = "text")
    private String report;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
