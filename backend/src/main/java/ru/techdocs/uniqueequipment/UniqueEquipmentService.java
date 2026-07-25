package ru.techdocs.uniqueequipment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.NotFoundException;
import ru.techdocs.common.Periodicity;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.equipment.EquipmentRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UniqueEquipmentService {

    private final UniqueEquipmentRepository repository;
    private final PlannedWorkRepository plannedWorkRepository;
    private final EquipmentRepository equipmentRepository;
    private final EngineeringSystemRepository systemRepository;

    // ---- нормализация и линковка ----

    /** Ключ модели без системы: наименование|модель|производитель, нормализованные. */
    public static String equipKey(String name, String model, String manufacturer) {
        return norm(name) + "|" + norm(model) + "|" + norm(manufacturer);
    }

    /**
     * Ключ уникальности с системой: модель + канонический токен инженерной системы.
     * Одна и та же модель в разных системах (коммутатор в СКУД и в Видеонаблюдении) —
     * это две разные записи реестра.
     */
    public static String normKey(String name, String model, String manufacturer, String system) {
        String sys = ru.techdocs.common.SystemNormalizer.canonical(system);
        return equipKey(name, model, manufacturer) + "|" + (sys == null ? "" : sys);
    }

    /**
     * Нормализация для сравнения: регистр, ё→е, все виды пробелов (в т.ч.
     * неразрывный U+00A0, который не ловит \s) и дефисов (–, —, ‑, −) сводятся
     * к обычным — иначе одинаковые модели из разных источников не совпадают.
     */
    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("[\\u2010-\\u2015\\u2212]", "-")   // разные дефисы/минус → -
                .replaceAll("[\\s\\u00A0\\u2000-\\u200B]+", " ") // любые пробелы → один
                .strip();
    }

    /** Находит уникальное оборудование по ключу (модель+система) без создания. */
    public java.util.Optional<UniqueEquipment> find(String name, String model, String manufacturer, String system) {
        return repository.findByNormKey(normKey(name, model, manufacturer, system));
    }

    /**
     * Поиск с запасным вариантом: сначала по (модель, система), иначе — по модели без
     * системы. Нужен для переиспользования эталонов, когда раздел эталона не распознался
     * как система (решение записано без системы), а объектное оборудование — с системой.
     */
    public java.util.Optional<UniqueEquipment> findWithFallback(String name, String model,
                                                                String manufacturer, String system) {
        var exact = find(name, model, manufacturer, system);
        if (exact.isPresent()) return exact;
        return repository.findFirstByEquipKeyOrderById(equipKey(name, model, manufacturer));
    }

    /** Находит или создаёт уникальное оборудование по ключу (модель+система). */
    public UniqueEquipment resolve(String name, String model, String manufacturer, String system) {
        String key = normKey(name, model, manufacturer, system);
        return repository.findByNormKey(key).orElseGet(() -> {
            UniqueEquipment ue = new UniqueEquipment();
            ue.setNormKey(key);
            ue.setEquipKey(equipKey(name, model, manufacturer));
            ue.setSystemType(ru.techdocs.common.SystemNormalizer.canonical(system));
            ue.setName(name);
            ue.setModel(model);
            ue.setManufacturer(manufacturer);
            return repository.save(ue);
        });
    }

    /**
     * Перепривязывает оборудование объектов к реестру по актуальному ключу и
     * удаляет осиротевшие записи-дубли (без оборудования, паспорта и работ).
     * Идемпотентно. Возвращает число переставленных строк оборудования.
     */
    public int syncFromEquipment() {
        Map<Long, String> systemNames = new HashMap<>();
        systemRepository.findAll().forEach(s -> systemNames.put(s.getId(), s.getName()));

        int changed = 0;
        for (Equipment eq : equipmentRepository.findAll()) {
            String system = systemNames.get(eq.getEngineeringSystemId());
            UniqueEquipment ue = resolve(eq.getName(), eq.getModel(), eq.getManufacturer(), system);
            if (!ue.getId().equals(eq.getUniqueEquipmentId())) {
                eq.setUniqueEquipmentId(ue.getId());
                equipmentRepository.save(eq);
                changed++;
            }
        }
        cleanupOrphans();
        return changed;
    }

    /** Удаляет уникальное оборудование, к которому больше ничего не привязано. */
    private void cleanupOrphans() {
        for (UniqueEquipment ue : repository.findAll()) {
            boolean hasPassport = ue.getPassportStoragePath() != null;
            boolean hasWorks = plannedWorkRepository.countByUniqueEquipmentId(ue.getId()) > 0;
            boolean hasEquipment = equipmentRepository.countByUniqueEquipmentId(ue.getId()) > 0;
            if (!hasEquipment && !hasPassport && !hasWorks) {
                repository.delete(ue);
            }
        }
    }

    // ---- реестр ----

    public record UniqueEquipmentView(UniqueEquipment equipment, long objectCount,
                                      long plannedWorkCount, String system) {}

    public List<UniqueEquipmentView> list() {
        Map<Long, String> systemNames = new HashMap<>();
        systemRepository.findAll().forEach(s -> systemNames.put(s.getId(), s.getName()));

        // группируем оборудование объектов по уникальному, чтобы посчитать систему
        Map<Long, List<Equipment>> byUnique = new HashMap<>();
        for (Equipment eq : equipmentRepository.findAll()) {
            if (eq.getUniqueEquipmentId() != null) {
                byUnique.computeIfAbsent(eq.getUniqueEquipmentId(), k -> new ArrayList<>()).add(eq);
            }
        }

        List<UniqueEquipmentView> views = new ArrayList<>();
        for (UniqueEquipment ue : repository.findAllByOrderByName()) {
            List<Equipment> eqs = byUnique.getOrDefault(ue.getId(), List.of());
            String system = dominantSystem(eqs, systemNames);
            if (system == null) system = ue.getSystemType();   // запись без объектного оборудования (только паспорт/эталон)
            views.add(new UniqueEquipmentView(ue, eqs.size(),
                    plannedWorkRepository.countByUniqueEquipmentId(ue.getId()), system));
        }
        return views;
    }

    /** Наиболее частая система среди привязанного оборудования. */
    private String dominantSystem(List<Equipment> eqs, Map<Long, String> systemNames) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Equipment eq : eqs) {
            String name = systemNames.get(eq.getEngineeringSystemId());
            if (name != null) counts.merge(name, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
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
