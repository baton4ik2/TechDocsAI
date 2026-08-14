package ru.techdocs.engineeringsystem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EngineeringSystemRepository extends JpaRepository<EngineeringSystem, Long> {
    List<EngineeringSystem> findByFacilityIdOrderById(Long facilityId);
}
