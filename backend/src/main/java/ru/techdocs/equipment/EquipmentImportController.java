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
    private final EquipmentRepository equipmentRepository;

    public record PreviewItem(String manufacturer, String name, String model,
                              BigDecimal quantity, String unit,
                              Integer duplicateGroup, boolean existsInRegistry) {}

    @PostMapping("/preview")
    public List<PreviewItem> preview(@RequestParam Long facilityId,
                                     @RequestParam("file") MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            throw new BadRequestException("Поддерживаются только файлы Excel (.xlsx, .xls)");
        }

        List<XlsxEquipmentExtractor.ExtractedItem> parsed;
        try (InputStream input = file.getInputStream()) {
            parsed = extractor.extract(file.getOriginalFilename(), input);
        } catch (Exception e) {
            throw new BadRequestException("Не удалось прочитать файл: " + e.getMessage());
        }
        if (parsed.isEmpty()) {
            throw new BadRequestException(
                    "В файле не найдена таблица оборудования. Нужны колонки: " +
                    "«Наименование», «Кол-во» (опционально «Модель/Тип», «Производитель», «Ед. изм.»).");
        }

        // существующий реестр объекта — для пометки «уже есть»
        Set<String> registryKeys = new HashSet<>();
        for (Equipment e : equipmentRepository.findFiltered(facilityId, null)) {
            registryKeys.add(key(e.getName(), e.getModel()));
        }

        // группировка дублей внутри файла
        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            var item = parsed.get(i);
            byKey.computeIfAbsent(key(item.name(), item.model()), k -> new ArrayList<>()).add(i);
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
                    item.quantity(), item.unit(),
                    groupOf.get(i),
                    registryKeys.contains(key(item.name(), item.model()))));
        }
        return result;
    }

    public record ImportItem(String manufacturer, String name, String model,
                             BigDecimal quantity, String unit, String comment) {}

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
        int created = 0;
        for (ImportItem item : request.items()) {
            if (item.name() == null || item.name().isBlank()
                    || item.quantity() == null || item.quantity().signum() <= 0) {
                continue;
            }
            Equipment equipment = new Equipment();
            equipment.setFacilityId(request.facilityId());
            equipment.setEngineeringSystemId(request.engineeringSystemId());
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

    private static String key(String name, String model) {
        return normalize(name) + "|" + normalize(model);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
