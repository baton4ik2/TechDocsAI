package ru.techdocs.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentPageRepository extends JpaRepository<DocumentPage, Long> {
    List<DocumentPage> findByDocumentIdOrderByPageNumber(Long documentId);

    @Transactional
    void deleteByDocumentId(Long documentId);
}
