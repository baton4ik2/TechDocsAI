package ru.techdocs.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long>, JpaSpecificationExecutor<Document> {

    long countByFacilityId(Long facilityId);

    long countByFacilityIdAndStatus(Long facilityId, String status);

    long countByEngineeringSystemId(Long systemId);

    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.engineeringSystemId = :target WHERE d.engineeringSystemId = :source")
    void reassignSystem(@Param("source") Long source, @Param("target") Long target);

    List<Document> findTop10ByOrderByCreatedAtDesc();

    List<Document> findByFacilityIdOrderByCreatedAtDesc(Long facilityId);

    @Query("""
            SELECT d FROM Document d
            WHERE d.facilityId = :facilityId
              AND (:systemId IS NULL OR d.engineeringSystemId = :systemId)
              AND (:typeId IS NULL OR d.documentTypeId = :typeId)
              AND (:status IS NULL OR d.status = :status)
            ORDER BY d.createdAt DESC
            """)
    List<Document> findFiltered(@Param("facilityId") Long facilityId,
                                @Param("systemId") Long systemId,
                                @Param("typeId") Long typeId,
                                @Param("status") String status);
}
