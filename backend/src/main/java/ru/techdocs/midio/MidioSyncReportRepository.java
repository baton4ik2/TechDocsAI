package ru.techdocs.midio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MidioSyncReportRepository extends JpaRepository<MidioSyncReport, Long> {
    Optional<MidioSyncReport> findTopByOrderByIdDesc();
}
