package ru.techdocs.uniqueequipment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.NotFoundException;
import ru.techdocs.common.Periodicity;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UniqueEquipmentService {

    private final UniqueEquipmentRepository repository;
    private final PlannedWorkRepository plannedWorkRepository;
    private final EquipmentRepository equipmentRepository;

    // ---- нормализация и линковка ----

    /** Ключ уникальности: наименование|модель|производитель, нормализованные. */
    public static String normKey(String name, String model, String manufacturer) {
        return norm(name) + "|" + norm(model) + "|" + norm(manufacturer);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replace('ё', 'е').replaceAll("\\s+", " ").strip();
    }

    /** Находит или создаёт уникальное оборудование по ключу и возвращает его. */
    public UniqueEquipment resolve(String name, String model, String manufacturer) {
        String key = normKey(name, model, manufacturer);
        return repository.findByNormKey(key).orElseGet(() -> {
            UniqueEquipment ue = new UniqueEquipment();
            ue.setNormKey(key);
            ue.setName(name);
            ue.setModel(model);
            ue.setManufacturer(manufacturer);
            return repository.save(ue);
        });
    }

    /** Привязывает всё непривязанное оборудование объектов к реестру. Идемпотентно. */
    public int syncFromEquipment() {
        List<Equipment> unlinked = equipmentRepository.findByUniqueEquipmentIdIsNull();
        int linked = 0;
        for (Equipment eq : unlinked) {
            UniqueEquipment ue = resolve(eq.getName(), eq.getModel(), eq.getManufacturer());
            eq.setUniqueEquipmentId(ue.getId());
            equipmentRepository.save(eq);
            linked++;
        }
        return linked;
    }

    // ---- реестр ----

    public record UniqueEquipmentView(UniqueEquipment equipment, long objectCount, long plannedWorkCount) {}

    public List<UniqueEquipmentView> list() {
        return repository.findAllByOrderByName().stream()
                .map(ue -> new UniqueEquipmentView(ue,
                        equipmentRepository.countByUniqueEquipmentId(ue.getId()),
                        plannedWorkRepository.countByUniqueEquipmentId(ue.getId())))
                .toList();
    }

    public UniqueEquipment get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Оборудование не найдено в реестре"));
    }

    public List<PlannedWork> plannedWorks(Long id) {
        get(id);
        return plannedWorkRepository.findByUniqueEquipmentIdOrderByPosition(id);
    }

    // ---- плановые работы ----

    public record PlannedWorkInput(String workType, String name, String workComposition,
                                   String periodicity, BigDecimal periodicityPerYear) {}

    public PlannedWork addPlannedWork(Long equipmentId, PlannedWorkInput input) {
        get(equipmentId);
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new BadRequestException("Укажите наименование плановой работы.");
        }
        PlannedWork w = new PlannedWork();
        w.setUniqueEquipmentId(equipmentId);
        w.setPosition((int) (plannedWorkRepository.countByUniqueEquipmentId(equipmentId) + 1));
        w.setSource(PlannedWork.SOURCE_MANUAL);
        apply(w, input);
        return plannedWorkRepository.save(w);
    }

    public PlannedWork updatePlannedWork(Long workId, PlannedWorkInput input) {
        PlannedWork w = plannedWorkRepository.findById(workId)
                .orElseThrow(() -> new NotFoundException("Плановая работа не найдена"));
        apply(w, input);
        return plannedWorkRepository.save(w);
    }

    public void deletePlannedWork(Long workId) {
        if (!plannedWorkRepository.existsById(workId)) throw new NotFoundException("Плановая работа не найдена");
        plannedWorkRepository.deleteById(workId);
    }

    private void apply(PlannedWork w, PlannedWorkInput in) {
        if (in.workType() != null) w.setWorkType(blank(in.workType()));
        if (in.name() != null) w.setName(in.name().strip());
        if (in.workComposition() != null) w.setWorkComposition(blank(in.workComposition()));
        if (in.periodicity() != null) {
            w.setPeriodicity(blank(in.periodicity()));
            if (in.periodicityPerYear() == null) w.setPeriodicityPerYear(Periodicity.perYear(in.periodicity()));
        }
        if (in.periodicityPerYear() != null) w.setPeriodicityPerYear(in.periodicityPerYear());
    }

    private String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
