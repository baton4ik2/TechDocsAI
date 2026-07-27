package ru.techdocs.uniqueequipment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UniqueEquipmentRepository extends JpaRepository<UniqueEquipment, Long> {
    Optional<UniqueEquipment> findByNormKey(String normKey);
    /** Запасной поиск по модели без системы (когда система эталона не распозналась). */
    Optional<UniqueEquipment> findFirstByEquipKeyOrderById(String equipKey);
    /** Легаси-записи (та же модель, система ещё не задана) — для усыновления при пере-синке. */
    List<UniqueEquipment> findByEquipKeyAndSystemTypeIsNull(String equipKey);
    /** Оборудование реестра в конкретной системе — для few-shot примеров из эталона. */
    List<UniqueEquipment> findBySystemType(String systemType);
    List<UniqueEquipment> findAllByOrderByName();
}
