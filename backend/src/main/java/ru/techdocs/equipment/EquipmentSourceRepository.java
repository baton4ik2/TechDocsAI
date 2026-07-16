package ru.techdocs.equipment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface EquipmentSourceRepository extends JpaRepository<EquipmentSource, Long> {
    List<EquipmentSource> findByEquipmentId(Long equipmentId);

    @Transactional
    void deleteByEquipmentId(Long equipmentId);
}
