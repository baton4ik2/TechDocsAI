package ru.techdocs.estimate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface EstimateRowRepository extends JpaRepository<EstimateRow, Long> {

    List<EstimateRow> findByEstimateIdOrderByPosition(Long estimateId);

    long countByEstimateId(Long estimateId);

    @Modifying
    @Transactional
    void deleteByEstimateId(Long estimateId);
}
