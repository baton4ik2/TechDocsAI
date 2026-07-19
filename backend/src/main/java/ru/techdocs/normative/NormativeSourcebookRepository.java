package ru.techdocs.normative;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NormativeSourcebookRepository extends JpaRepository<NormativeSourcebook, Long> {
    List<NormativeSourcebook> findAllByOrderByCreatedAtDesc();
}
