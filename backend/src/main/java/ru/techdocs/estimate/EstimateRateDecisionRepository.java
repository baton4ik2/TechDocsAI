package ru.techdocs.estimate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EstimateRateDecisionRepository extends JpaRepository<EstimateRateDecision, Long> {

    List<EstimateRateDecision> findByUniqueEquipmentIdOrderByOperationKey(Long uniqueEquipmentId);

    Optional<EstimateRateDecision> findByUniqueEquipmentIdAndOperationKey(Long uniqueEquipmentId, String operationKey);

    List<EstimateRateDecision> findAllByOrderByUpdatedAtDesc();
}
