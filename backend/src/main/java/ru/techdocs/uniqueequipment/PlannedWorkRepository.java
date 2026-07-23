package ru.techdocs.uniqueequipment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PlannedWorkRepository extends JpaRepository<PlannedWork, Long> {

    List<PlannedWork> findByUniqueEquipmentIdOrderByPosition(Long uniqueEquipmentId);

    long countByUniqueEquipmentId(Long uniqueEquipmentId);

    @Modifying
    @Transactional
    void deleteByUniqueEquipmentIdAndSource(Long uniqueEquipmentId, String source);
}
