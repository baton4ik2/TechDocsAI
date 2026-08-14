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
import ru.techdocs.uniqueequipment.PlannedWork;
import ru.techdocs.uniqueequipment.PlannedWorkRepository;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentRepository;
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
    @Autowired UniqueEquipmentRepository uniqueRepository;
    @Autowired PlannedWorkRepository plannedWorkRepository;

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
    void registryShowsStandardSystemNameForRecordsWithoutObjectEquipment() {
        // запись из эталона знает систему только каноническим токеном («апс»);
        // в реестре она должна называться как в справочнике, а не токеном —
        // иначе рядом с «Пожарной сигнализацией» появляется лишний фильтр «апс»
        UniqueEquipment fromEtalon = new UniqueEquipment();
        fromEtalon.setName("Адресная метка");
        fromEtalon.setModel("АМ-4 прот. R3");
        fromEtalon.setSystemType("апс");
        fromEtalon.setEquipKey(UniqueEquipmentService.equipKey("Адресная метка", "АМ-4 прот. R3", null));
        fromEtalon.setNormKey(UniqueEquipmentService.normKey("Адресная метка", "АМ-4 прот. R3", null, "апс"));
        uniqueRepository.saveAndFlush(fromEtalon);

        var view = uniqueEquipmentService.list().stream()
                .filter(v -> v.equipment().getId().equals(fromEtalon.getId()))
                .findFirst().orElseThrow();

        assertThat(view.objectCount()).isZero();
        assertThat(view.system()).isEqualTo("Пожарная сигнализация");
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

    @Test
    void resyncAdoptsLegacySystemlessRecordKeepingPlannedWorks() {
        long facilityId = facility();
        long skud = system(facilityId, "СКУД");
        commutator(facilityId, skud);

        // легаси-запись (как до миграции): equip_key задан, система пустая, есть плановая работа
        UniqueEquipment legacy = new UniqueEquipment();
        String ekey = UniqueEquipmentService.equipKey("24-портовый Ethernet-коммутатор", "LTV-3S24G4C-P", "LTV");
        legacy.setNormKey(ekey);            // прежний ключ был без системы
        legacy.setEquipKey(ekey);
        legacy.setName("24-портовый Ethernet-коммутатор");
        legacy.setModel("LTV-3S24G4C-P");
        legacy.setManufacturer("LTV");
        legacy = uniqueRepository.saveAndFlush(legacy);
        long legacyId = legacy.getId();

        PlannedWork work = new PlannedWork();
        work.setUniqueEquipmentId(legacyId);
        work.setName("Технический осмотр");
        work.setSource(PlannedWork.SOURCE_MANUAL);
        plannedWorkRepository.saveAndFlush(work);

        uniqueEquipmentService.syncFromEquipment();

        // легаси-запись усыновлена на месте: тот же id, теперь с системой и с той же работой
        UniqueEquipment after = uniqueRepository.findById(legacyId).orElseThrow();
        assertThat(after.getSystemType()).isEqualTo("скуд");
        assertThat(plannedWorkRepository.countByUniqueEquipmentId(legacyId)).isEqualTo(1);
        // оборудование СКУД привязано именно к усыновлённой записи (новый дубль не создан)
        List<UniqueEquipmentService.UniqueEquipmentView> commutators = uniqueEquipmentService.list().stream()
                .filter(v -> "LTV-3S24G4C-P".equals(v.equipment().getModel())).toList();
        assertThat(commutators).hasSize(1);
        assertThat(commutators.get(0).equipment().getId()).isEqualTo(legacyId);
        assertThat(commutators.get(0).objectCount()).isEqualTo(1);
    }
}
