package ru.techdocs.object;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FacilityRepository extends JpaRepository<Facility, Long> {
    List<Facility> findAllByOrderByCreatedAtDesc();

    List<Facility> findByNameContainingIgnoreCaseOrderByCreatedAtDesc(String name);
}
