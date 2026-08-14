package ru.techdocs.estimate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EstimateRepository extends JpaRepository<Estimate, Long> {
    List<Estimate> findByFacilityIdOrderByCreatedAtDesc(Long facilityId);
    List<Estimate> findAllByOrderByCreatedAtDesc();
}
