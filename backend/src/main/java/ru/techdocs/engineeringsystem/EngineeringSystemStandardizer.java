package ru.techdocs.engineeringsystem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.common.SystemCatalog;
import ru.techdocs.document.DocumentRepository;
import ru.techdocs.equipment.EquipmentRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Выравнивание уже заведённых систем под справочник {@link SystemCatalog}.
 * До введения справочника имена были свободными, и «апс» с «Пожарной
 * сигнализацией» жили на одном объекте как две разные системы — реестр
 * оборудования из-за этого двоился.
 * <p>
 * На каждом старте, идемпотентно: распознанные системы переименовываются в
 * стандартное название; дубли одной системы на объекте сливаются (оборудование
 * и документы переезжают в выжившую). Нераспознанные имена не трогаются —
 * удалить или переименовать их может только инженер.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EngineeringSystemStandardizer {

    private final EngineeringSystemRepository systemRepository;
    private final EquipmentRepository equipmentRepository;
    private final DocumentRepository documentRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void standardize() {
        int renamed = 0;
        int merged = 0;

        // (объект, стандартное имя) → выжившая система
        Map<String, EngineeringSystem> survivors = new LinkedHashMap<>();
        List<EngineeringSystem> all = systemRepository.findAll();
        for (EngineeringSystem system : all) {
            String standard = SystemCatalog.standardName(system.getName());
            if (standard == null) continue;

            String key = system.getFacilityId() + "|" + standard;
            EngineeringSystem survivor = survivors.get(key);
            if (survivor == null) {
                survivors.put(key, system);
                if (!standard.equals(system.getName())) {
                    system.setName(standard);
                    systemRepository.save(system);
                    renamed++;
                }
            } else {
                equipmentRepository.reassignSystem(system.getId(), survivor.getId());
                documentRepository.reassignSystem(system.getId(), survivor.getId());
                systemRepository.delete(system);
                merged++;
            }
        }
        if (renamed > 0 || merged > 0) {
            log.info("Справочник систем: переименовано {}, слито дублей {}", renamed, merged);
        }
    }
}
