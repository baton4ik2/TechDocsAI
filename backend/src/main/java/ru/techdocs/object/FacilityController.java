package ru.techdocs.object;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.NotFoundException;
import ru.techdocs.document.DocumentRepository;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.equipment.EquipmentRepository;

import java.util.List;

@RestController
@RequestMapping("/api/facilities")
@RequiredArgsConstructor
public class FacilityController {

    private final FacilityRepository facilityRepository;
    private final EngineeringSystemRepository systemRepository;
    private final EquipmentRepository equipmentRepository;
    private final DocumentRepository documentRepository;
    private final FacilityStatsService statsService;

    public record FacilityRequest(@NotBlank String name, String address, String description,
                                  String status, List<String> systems) {}

    @GetMapping
    public List<FacilityStatsService.FacilityWithStats> list(@RequestParam(required = false) String search) {
        List<Facility> facilities = (search == null || search.isBlank())
                ? facilityRepository.findAllByOrderByCreatedAtDesc()
                : facilityRepository.findByNameContainingIgnoreCaseOrderByCreatedAtDesc(search.trim());
        return statsService.withStats(facilities);
    }

    @GetMapping("/{id}")
    public FacilityStatsService.FacilityWithStats get(@PathVariable Long id) {
        Facility facility = facilityRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Объект не найден"));
        return statsService.withStats(List.of(facility)).getFirst();
    }

    @PostMapping
    public FacilityStatsService.FacilityWithStats create(@Valid @RequestBody FacilityRequest request) {
        Facility facility = new Facility();
        facility.setName(request.name());
        facility.setAddress(request.address());
        facility.setDescription(request.description());
        if (request.status() != null) facility.setStatus(request.status());
        facility = facilityRepository.save(facility);

        if (request.systems() != null) {
            for (String systemName : request.systems()) {
                if (systemName == null || systemName.isBlank()) continue;
                EngineeringSystem system = new EngineeringSystem();
                system.setFacilityId(facility.getId());
                system.setName(systemName.trim());
                systemRepository.save(system);
            }
        }
        return statsService.withStats(List.of(facility)).getFirst();
    }

    @PutMapping("/{id}")
    public FacilityStatsService.FacilityWithStats update(@PathVariable Long id,
                                                         @Valid @RequestBody FacilityRequest request) {
        Facility facility = facilityRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Объект не найден"));
        facility.setName(request.name());
        facility.setAddress(request.address());
        facility.setDescription(request.description());
        if (request.status() != null) facility.setStatus(request.status());
        facilityRepository.save(facility);
        return statsService.withStats(List.of(facility)).getFirst();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!facilityRepository.existsById(id)) {
            throw new NotFoundException("Объект не найден");
        }
        facilityRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // --- Инженерные системы объекта ---

    public record SystemRequest(@NotBlank String name, String code, String description) {}

    @GetMapping("/{id}/systems")
    public List<FacilityStatsService.SystemWithStats> systems(@PathVariable Long id) {
        return statsService.systemsWithStats(id);
    }

    @PostMapping("/{id}/systems")
    public EngineeringSystem addSystem(@PathVariable Long id, @Valid @RequestBody SystemRequest request) {
        if (!facilityRepository.existsById(id)) {
            throw new NotFoundException("Объект не найден");
        }
        EngineeringSystem system = new EngineeringSystem();
        system.setFacilityId(id);
        system.setName(request.name().trim());
        system.setCode(request.code());
        system.setDescription(request.description());
        return systemRepository.save(system);
    }

    @PatchMapping("/{id}/systems/{systemId}")
    public EngineeringSystem renameSystem(@PathVariable Long id, @PathVariable Long systemId,
                                          @Valid @RequestBody SystemRequest request) {
        EngineeringSystem system = systemRepository.findById(systemId)
                .orElseThrow(() -> new NotFoundException("Система не найдена"));
        system.setName(request.name().trim());
        return systemRepository.save(system);
    }

    public record MergeSystemRequest(Long targetSystemId) {}

    /** Переносит оборудование и документы из системы в целевую, затем удаляет исходную. */
    @PostMapping("/{id}/systems/{systemId}/merge")
    @Transactional
    public ResponseEntity<Void> mergeSystem(@PathVariable Long id, @PathVariable Long systemId,
                                            @RequestBody MergeSystemRequest request) {
        if (request.targetSystemId() == null || request.targetSystemId().equals(systemId)) {
            throw new BadRequestException("Не выбрана целевая система для объединения");
        }
        systemRepository.findById(systemId)
                .orElseThrow(() -> new NotFoundException("Система не найдена"));
        systemRepository.findById(request.targetSystemId())
                .orElseThrow(() -> new NotFoundException("Целевая система не найдена"));

        equipmentRepository.reassignSystem(systemId, request.targetSystemId());
        documentRepository.reassignSystem(systemId, request.targetSystemId());
        systemRepository.deleteById(systemId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/systems/{systemId}")
    public ResponseEntity<Void> deleteSystem(@PathVariable Long id, @PathVariable Long systemId) {
        systemRepository.deleteById(systemId);
        return ResponseEntity.noContent().build();
    }
}
