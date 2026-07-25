package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;
import ru.techdocs.object.Facility;
import ru.techdocs.object.FacilityRepository;
import ru.techdocs.uniqueequipment.UniqueEquipmentService;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Система — часть идентичности: одна модель в разных системах → две записи реестра. */
@Transactional
class UniqueEquipmentSystemTest extends IntegrationTestBase {

    @Autowired EngineeringSystemRepository systemRepository;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired UniqueEquipmentService uniqueEquipmentService;
    @Autowired FacilityRepository facilityRepository;

    private long facility() {
        Facility f = new Facility();
        f.setName("Объект с двумя системами");
        return facilityRepository.saveAndFlush(f).getId();
    }

    private long system(long facilityId, String name) {
        EngineeringSystem s = new EngineeringSystem();
        s.setFacilityId(facilityId);
        s.setName(name);
        return systemRepository.saveAndFlush(s).getId();
    }

    private void commutator(long facilityId, long systemId) {
        Equipment e = new Equipment();
        e.setFacilityId(facilityId);
        e.setEngineeringSystemId(systemId);
        e.setName("24-портовый Ethernet-коммутатор");
        e.setModel("LTV-3S24G4C-P");
        e.setManufacturer("LTV");
        e.setQuantity(new BigDecimal("5"));
        equipmentRepository.saveAndFlush(e);
    }

    @Test
    void sameModelInTwoSystemsSplitsIntoTwoRegistryEntries() {
        long facilityId = facility();
        long video = system(facilityId, "Видеонаблюдение");
        long skud = system(facilityId, "СКУД");
        commutator(facilityId, video);
        commutator(facilityId, skud);

        uniqueEquipmentService.syncFromEquipment();

        List<UniqueEquipmentService.UniqueEquipmentView> views = uniqueEquipmentService.list().stream()
                .filter(v -> "LTV-3S24G4C-P".equals(v.equipment().getModel()))
                .toList();

        // две записи — по одной на систему
        assertThat(views).hasSize(2);
        assertThat(views).extracting(v -> v.equipment().getSystemType())
                .containsExactlyInAnyOrder("видеонаблюдение", "скуд");
        assertThat(views).allSatisfy(v -> assertThat(v.objectCount()).isEqualTo(1));
    }
}
