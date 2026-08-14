package ru.techdocs.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    @Transactional
    void deleteByDocumentId(Long documentId);
}
