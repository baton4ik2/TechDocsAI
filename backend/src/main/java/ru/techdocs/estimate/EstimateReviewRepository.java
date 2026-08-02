package ru.techdocs.estimate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EstimateReviewRepository extends JpaRepository<EstimateReview, Long> {

    Optional<EstimateReview> findByEstimateId(Long estimateId);
}
