package ru.techdocs.equipment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    long countByFacilityId(Long facilityId);

    @Modifying
    @Transactional
    @Query("UPDATE Equipment e SET e.engineeringSystemId = :target WHERE e.engineeringSystemId = :source")
    void reassignSystem(@Param("source") Long source, @Param("target") Long target);

    @Query("SELECT COALESCE(SUM(e.quantity), 0) FROM Equipment e WHERE e.facilityId = :facilityId")
    BigDecimal sumQuantityByFacility(@Param("facilityId") Long facilityId);

    @Query("""
            SELECT e FROM Equipment e
            WHERE (:facilityId IS NULL OR e.facilityId = :facilityId)
              AND (:systemId IS NULL OR e.engineeringSystemId = :systemId)
            ORDER BY e.name, e.model
            """)
    List<Equipment> findFiltered(@Param("facilityId") Long facilityId,
                                 @Param("systemId") Long systemId);

    @Query("""
            SELECT e FROM Equipment e
            WHERE (:facilityId IS NULL OR e.facilityId = :facilityId)
              AND (:systemId IS NULL OR e.engineeringSystemId = :systemId)
              AND (LOWER(e.name) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR LOWER(e.model) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR LOWER(e.manufacturer) LIKE LOWER(CONCAT('%', :term, '%')))
            """)
    List<Equipment> searchByTerm(@Param("facilityId") Long facilityId,
                                 @Param("systemId") Long systemId,
                                 @Param("term") String term);
}
