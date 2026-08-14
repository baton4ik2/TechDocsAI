package ru.techdocs.pkm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PkmDocumentRepository extends JpaRepository<PkmDocument, Long> {
    List<PkmDocument> findAllByOrderByCreatedAtDesc();
}
