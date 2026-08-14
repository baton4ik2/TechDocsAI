package ru.techdocs.estimate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EstimateExportRepository extends JpaRepository<EstimateExport, Long> {

    List<EstimateExport> findByEstimateIdOrderByVersion(Long estimateId);
}
