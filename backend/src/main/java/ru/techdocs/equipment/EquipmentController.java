package ru.techdocs.equipment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.NotFoundException;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/equipment")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentRepository equipmentRepository;
    private final EquipmentSourceRepository sourceRepository;
    private final ru.techdocs.document.DocumentRepository documentRepository;

    public record EquipmentRequest(Long facilityId, Long engineeringSystemId, String manufacturer,
                                   @NotBlank String name, String model, String modification,
                                   BigDecimal quantity, String unit, String location,
                                   String comment, String status) {}

    public record SourceRef(Long documentId, String documentName, Integer pageNumber) {}

    public record EquipmentDto(Long id, Long facilityId, Long engineeringSystemId,
                               String manufacturer, String name, String model, String modification,
                               java.math.BigDecimal quantity, String unit, String location,
                               String comment, String status, java.time.Instant createdAt,
                               List<SourceRef> sources) {}

    @GetMapping
    public List<EquipmentDto> list(@RequestParam(required = false) Long facilityId,
                                   @RequestParam(required = false) Long systemId,
                                   @RequestParam(required = false) String search) {
        List<Equipment> items = (search != null && !search.isBlank())
                ? equipmentRepository.searchByTerm(facilityId, systemId, search.trim())
                : equipmentRepository.findFiltered(facilityId, systemId);
        if (items.isEmpty()) return List.of();

        // источники всех позиций одним запросом + имена документов
        var sourcesByEquipment = sourceRepository
                .findByEquipmentIdIn(items.stream().map(Equipment::getId).toList())
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(EquipmentSource::getEquipmentId));
        var documentNames = documentRepository
                .findAllById(sourcesByEquipment.values().stream()
                        .flatMap(List::stream)
                        .map(EquipmentSource::getDocumentId)
                        .distinct()
                        .toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        ru.techdocs.document.Document::getId,
                        ru.techdocs.document.Document::getOriginalFilename));

        return items.stream().map(e -> new EquipmentDto(
                e.getId(), e.getFacilityId(), e.getEngineeringSystemId(),
                e.getManufacturer(), e.getName(), e.getModel(), e.getModification(),
                e.getQuantity(), e.getUnit(), e.getLocation(),
                e.getComment(), e.getStatus(), e.getCreatedAt(),
                sourcesByEquipment.getOrDefault(e.getId(), List.of()).stream()
                        .map(s -> new SourceRef(s.getDocumentId(),
                                documentNames.getOrDefault(s.getDocumentId(), "Документ #" + s.getDocumentId()),
                                s.getPageNumber()))
                        .toList()
        )).toList();
    }

    @GetMapping("/{id}")
    public Equipment get(@PathVariable Long id) {
        return equipmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Оборудование не найдено"));
    }

    @GetMapping("/{id}/sources")
    public List<EquipmentSource> sources(@PathVariable Long id) {
        return sourceRepository.findByEquipmentId(id);
    }

    @PostMapping
    public Equipment create(@Valid @RequestBody EquipmentRequest request) {
        if (request.facilityId() == null) {
            throw new BadRequestException("Не указан объект");
        }
        Equipment equipment = new Equipment();
        apply(equipment, request);
        equipment.setFacilityId(request.facilityId());
        equipment.setStatus(request.status() != null ? request.status() : Equipment.STATUS_CONFIRMED);
        return equipmentRepository.save(equipment);
    }

    @PutMapping("/{id}")
    public Equipment update(@PathVariable Long id, @Valid @RequestBody EquipmentRequest request) {
        Equipment equipment = get(id);
        apply(equipment, request);
        if (request.status() != null) equipment.setStatus(request.status());
        return equipmentRepository.save(equipment);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        equipmentRepository.delete(get(id));
        return ResponseEntity.noContent().build();
    }

    public record MergeRequest(List<Long> ids) {}

    /** Объединяет несколько записей в одну: суммирует количество, переносит источники. */
    @PostMapping("/merge")
    @Transactional
    public Equipment merge(@RequestBody MergeRequest request) {
        if (request.ids() == null || request.ids().size() < 2) {
            throw new BadRequestException("Выберите минимум две записи для объединения");
        }
        List<Equipment> items = equipmentRepository.findAllById(request.ids());
        if (items.size() < 2) {
            throw new NotFoundException("Записи не найдены");
        }
        Equipment target = items.getFirst();
        BigDecimal total = BigDecimal.ZERO;
        for (Equipment item : items) {
            total = total.add(item.getQuantity());
        }
        target.setQuantity(total);
        target.setStatus(Equipment.STATUS_NEEDS_REVIEW);
        equipmentRepository.save(target);

        for (Equipment item : items.subList(1, items.size())) {
            for (EquipmentSource source : sourceRepository.findByEquipmentId(item.getId())) {
                source.setEquipmentId(target.getId());
                sourceRepository.save(source);
            }
            equipmentRepository.delete(item);
        }
        return target;
    }

    private void apply(Equipment equipment, EquipmentRequest request) {
        if (request.engineeringSystemId() != null) equipment.setEngineeringSystemId(request.engineeringSystemId());
        equipment.setManufacturer(request.manufacturer());
        equipment.setName(request.name());
        equipment.setModel(request.model());
        equipment.setModification(request.modification());
        if (request.quantity() != null) equipment.setQuantity(request.quantity());
        if (request.unit() != null && !request.unit().isBlank()) equipment.setUnit(request.unit());
        equipment.setLocation(request.location());
        equipment.setComment(request.comment());
    }
}
