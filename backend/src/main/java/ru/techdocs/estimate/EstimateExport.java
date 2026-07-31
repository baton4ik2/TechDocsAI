package ru.techdocs.estimate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Выгрузка сметы в XLSX: номер версии и когда она сделана (блок «История версий»). */
@Entity
@Table(name = "estimate_exports")
@Getter
@Setter
@NoArgsConstructor
public class EstimateExport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "estimate_id", nullable = false)
    private Long estimateId;

    @Column(nullable = false)
    private int version;

    @Column(name = "exported_at", nullable = false)
    private Instant exportedAt = Instant.now();
}
