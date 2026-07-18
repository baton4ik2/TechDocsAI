package ru.techdocs.equipment;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.techdocs.common.BadRequestException;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

/**
 * Импорт реестра оборудования из готовой Excel-таблицы пользователя.
 * Двухшаговый: preview показывает разобранные строки с пометками дублей
 * (внутри файла и с существующим реестром), import сохраняет решения пользователя.
 */
@RestController
@RequestMapping("/api/equipment/import")
@RequiredArgsConstructor
public class EquipmentImportController {

    private final XlsxEquipmentExtractor extractor;
    private final WideRegistryParser wideParser;
    private final EquipmentRepository equipmentRepository;
    private final ru.techdocs.engineeringsystem.EngineeringSystemRepository systemRepository;

    public record PreviewItem(String manufacturer, String name, String model,
                              BigDecimal quantity, String unit, String systemName,
                              boolean quantityMissing,
                              Integer duplicateGroup, boolean existsInRegistry) {}

    /** Промежуточное представление строки из любого парсера. */
    private record ParsedRow(String systemName, String manufacturer, String name,
                             String model, BigDecimal quantity, String unit) {}

    @PostMapping("/preview")
    public List<PreviewItem> preview(@RequestParam Long facilityId,
                                     @RequestParam("file") MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            throw new BadRequestException("Поддерживаются только файлы Excel (.xlsx, .xls)");
        }

        // сначала пробуем «широкий» формат (системы горизонтальными блоками),
        // затем обычную плоскую таблицу
        List<ParsedRow> parsed = new ArrayList<>();
        try (InputStream input = file.getInputStream()) {
            for (var w : wideParser.parse(file.getOriginalFilename(), input)) {
                parsed.add(new ParsedRow(w.systemName(), w.manufacturer(), w.name(),
                        w.model(), w.quantity(), w.unit()));
            }
        } catch (Exception e) {
            throw new BadRequestException("Не удалось прочитать файл: " + e.getMessage());
        }
        if (parsed.isEmpty()) {
            try (InputStream input = file.getInputStream()) {
                for (var f : extractor.extract(file.getOriginalFilename(), input)) {
                    parsed.add(new ParsedRow(null, f.manufacturer(), f.name(),
                            f.model(), f.quantity(), f.unit()));
                }
            } catch (Exception e) {
                throw new BadRequestException("Не удалось прочитать файл: " + e.getMessage());
            }
        }
        if (parsed.isEmpty()) {
            throw new BadRequestException(
                    "В файле не найдена таблица оборудования. Нужны колонки: " +
                    "«Наименование», «Кол-во» (опционально «Модель/Тип», «Производитель», «Ед. изм.»).");
        }

        // существующий реестр объекта: ключ включает систему, чтобы одинаковая
        // модель в разных системах не считалась дублем
        Map<Long, String> systemNames = new HashMap<>();
        for (var s : systemRepository.findByFacilityIdOrderById(facilityId)) {
            systemNames.put(s.getId(), s.getName());
        }
        Set<String> registryKeys = new HashSet<>();
        for (Equipment e : equipmentRepository.findFiltered(facilityId, null)) {
            String sysName = e.getEngineeringSystemId() == null ? null
                    : systemNames.get(e.getEngineeringSystemId());
            registryKeys.add(key(sysName, e.getName(), e.getModel()));
        }

        // группировка дублей внутри файла (в пределах системы)
        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            var item = parsed.get(i);
            byKey.computeIfAbsent(key(item.systemName(), item.name(), item.model()),
                    k -> new ArrayList<>()).add(i);
        }
        Map<Integer, Integer> groupOf = new HashMap<>();
        int groupCounter = 0;
        for (var entry : byKey.entrySet()) {
            if (entry.getValue().size() > 1) {
                groupCounter++;
                for (int index : entry.getValue()) {
                    groupOf.put(index, groupCounter);
                }
            }
        }

        List<PreviewItem> result = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            var item = parsed.get(i);
            result.add(new PreviewItem(
                    blankToNull(item.manufacturer()), item.name(), blankToNull(item.model()),
                    item.quantity() == null ? BigDecimal.ONE : item.quantity(),
                    item.unit(), item.systemName(),
                    item.quantity() == null,
                    groupOf.get(i),
                    registryKeys.contains(key(item.systemName(), item.name(), item.model()))));
        }
        return result;
    }

    public record ImportItem(String manufacturer, String name, String model,
                             BigDecimal quantity, String unit, String comment,
                             String systemName) {}

    public record ImportRequest(Long facilityId, Long engineeringSystemId,
                                String fileName, List<ImportItem> items) {}

    @PostMapping
    @Transactional
    public Map<String, Object> importItems(@RequestBody ImportRequest request) {
        if (request.facilityId() == null) {
            throw new BadRequestException("Не указан объект");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BadRequestException("Нет позиций для импорта");
        }

        // системы объекта: ищем по имени (без регистра), отсутствующие создаём
        Map<String, Long> systemByName = new HashMap<>();
        for (var s : systemRepository.findByFacilityIdOrderById(request.facilityId())) {
            systemByName.put(normalize(s.getName()), s.getId());
        }

        int created = 0;
        for (ImportItem item : request.items()) {
            if (item.name() == null || item.name().isBlank()
                    || item.quantity() == null || item.quantity().signum() <= 0) {
                continue;
            }
            Long systemId = request.engineeringSystemId();
            if (item.systemName() != null && !item.systemName().isBlank()) {
                String key = normalize(item.systemName());
                // синонимы: «Пожарная сигнализация» из файла = существующая «АПС»
                if (!systemByName.containsKey(key)) {
                    String alias = SYSTEM_ALIASES.get(key);
                    if (alias != null && systemByName.containsKey(alias)) {
                        key = alias;
                    }
                }
                systemId = systemByName.computeIfAbsent(key, k -> {
                    var system = new ru.techdocs.engineeringsystem.EngineeringSystem();
                    system.setFacilityId(request.facilityId());
                    system.setName(item.systemName().strip());
                    return systemRepository.save(system).getId();
                });
            }
            Equipment equipment = new Equipment();
            equipment.setFacilityId(request.facilityId());
            equipment.setEngineeringSystemId(systemId);
            equipment.setManufacturer(blankToNull(item.manufacturer()));
            equipment.setName(item.name().strip());
            equipment.setModel(blankToNull(item.model()));
            equipment.setQuantity(item.quantity());
            equipment.setUnit(item.unit() == null || item.unit().isBlank() ? "шт." : item.unit());
            // таблица пользователя — проверенные данные, сразу «Подтверждено»
            equipment.setStatus(Equipment.STATUS_CONFIRMED);
            String note = "Импортировано из «" + (request.fileName() == null ? "файла" : request.fileName()) + "»";
            equipment.setComment(item.comment() == null ? note : note + ". " + item.comment());
            equipmentRepository.save(equipment);
            created++;
        }
        return Map.of("created", created);
    }

    /** Синонимы названий систем (нормализованные): вариант из файла → принятое имя. */
    private static final Map<String, String> SYSTEM_ALIASES = Map.ofEntries(
            Map.entry("пожарнаясигнализация", "апс"),
            Map.entry("автоматическаяпожарнаясигнализация", "апс"),
            Map.entry("аупс", "апс"),
            Map.entry("спс", "апс"),
            Map.entry("охраннаясигнализация", "ос"),
            Map.entry("энергоучет", "аскуэ"),
            Map.entry("учетэнергоресурсов", "аскуэ"),
            Map.entry("ктсо", "скуд"),
            Map.entry("видеонаблюдениеcctv", "видеонаблюдение"),
            Map.entry("cctv", "видеонаблюдение"));

    private static String key(String systemName, String name, String model) {
        return normalize(systemName) + "|" + normalize(name) + "|" + normalize(model);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
