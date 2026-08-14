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
    private final UniqueEquipmentPassportService passportService;

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

        java.util.Set<Long> claimed = new java.util.HashSet<>();
        int changed = 0;
        for (Equipment eq : equipmentRepository.findAll()) {
            String system = systemNames.get(eq.getEngineeringSystemId());
            UniqueEquipment ue = resolveForSync(eq.getName(), eq.getModel(), eq.getManufacturer(), system, claimed);
            claimed.add(ue.getId());
            if (!ue.getId().equals(eq.getUniqueEquipmentId())) {
                eq.setUniqueEquipmentId(ue.getId());
                equipmentRepository.save(eq);
                changed++;
            }
        }
        cleanupOrphans();
        return changed;
    }

    /**
     * Резолв для пере-синка с усыновлением легаси-записи. Прежние записи реестра были
     * без системы (norm_key = equip_key). Чтобы не потерять паспорт/плановые работы при
     * переходе на ключ с системой, легаси-запись (equip_key совпал, система не задана,
     * есть паспорт или работы, ещё не занята в этом прогоне) обновляется на месте —
     * получает систему и новый norm_key. Иначе — обычный resolve (найдёт/создаст).
     */
    private UniqueEquipment resolveForSync(String name, String model, String manufacturer,
                                           String system, java.util.Set<Long> claimed) {
        String key = normKey(name, model, manufacturer, system);
        var exact = repository.findByNormKey(key);
        if (exact.isPresent()) return exact.get();

        String ekey = equipKey(name, model, manufacturer);
        for (UniqueEquipment legacy : repository.findByEquipKeyAndSystemTypeIsNull(ekey)) {
            if (claimed.contains(legacy.getId())) continue;
            boolean worthKeeping = legacy.getPassportStoragePath() != null
                    || plannedWorkRepository.countByUniqueEquipmentId(legacy.getId()) > 0;
            if (!worthKeeping) continue;
            legacy.setSystemType(ru.techdocs.common.SystemNormalizer.canonical(system));
            legacy.setNormKey(key);
            return repository.save(legacy);
        }
        return resolve(name, model, manufacturer, system);
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
            passportService.releaseIfStale(ue);
            List<Equipment> eqs = byUnique.getOrDefault(ue.getId(), List.of());
            String system = dominantSystem(eqs, systemNames);
            // запись без объектного оборудования (только паспорт/эталон) знает свою
            // систему лишь каноническим токеном («апс») — показываем стандартное
            // название справочника, иначе в реестре появляется лишний фильтр
            if (system == null) system = displaySystem(ue.getSystemType());
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
                .map(this::displaySystem)
                .orElse(null);
    }

    /**
     * Отображаемое название системы: стандартное из справочника, иначе исходное.
     * Нераспознанное имя не выдумываем — показываем как есть.
     */
    private String displaySystem(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String standard = ru.techdocs.common.SystemCatalog.standardName(raw);
        return standard != null ? standard : raw;
    }

    public UniqueEquipment get(Long id) {
        UniqueEquipment ue = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Оборудование не найдено в реестре"));
        // зависший разбор освобождаем при чтении: иначе карточка вечно «Обрабатывается»
        passportService.releaseIfStale(ue);
        return ue;
    }

    /** Оборудование реестра в канонической системе (для few-shot примеров из эталона). */
    public List<UniqueEquipment> bySystemType(String canonicalSystem) {
        return canonicalSystem == null ? List.of() : repository.findBySystemType(canonicalSystem);
    }

    /** Всё оборудование реестра (запасной слой эталона — по всем системам). */
    public List<UniqueEquipment> all() {
        return repository.findAll();
    }

    /**
     * Массовая отвязка плановых работ одного источника по всему реестру:
     * PASSPORT — извлечённые из паспортов, MIDIO — перенесённые из Midio.
     * Ручные работы намеренно недоступны: их инженер завёл штучно, и снести их
     * одной кнопкой значит потерять невосстановимое.
     */
    @org.springframework.transaction.annotation.Transactional
    public long unlinkBySource(String source) {
        if (!PlannedWork.SOURCE_PASSPORT.equals(source) && !PlannedWork.SOURCE_MIDIO.equals(source)) {
            throw new BadRequestException("Массовая отвязка доступна для источников PASSPORT и MIDIO.");
        }
        return plannedWorkRepository.deleteBySource(source);
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
