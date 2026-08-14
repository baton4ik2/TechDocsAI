package ru.techdocs.estimate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EstimateRateDecisionRepository extends JpaRepository<EstimateRateDecision, Long> {

    List<EstimateRateDecision> findByUniqueEquipmentIdOrderByOperationKey(Long uniqueEquipmentId);

    /** Решения одной категории операции — их может быть несколько (разные расценки). */
    List<EstimateRateDecision> findByUniqueEquipmentIdAndOperationKey(Long uniqueEquipmentId, String operationKey);

    Optional<EstimateRateDecision> findByUniqueEquipmentIdAndOperationKeyAndRateCode(
            Long uniqueEquipmentId, String operationKey, String rateCode);

    List<EstimateRateDecision> findAllByOrderByUpdatedAtDesc();
}
