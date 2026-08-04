package ru.techdocs.uniqueequipment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Реестр уникального оборудования: паспорта и плановые работы. */
@RestController
@RequestMapping("/api/unique-equipment")
@RequiredArgsConstructor
public class UniqueEquipmentController {

    private final UniqueEquipmentService service;
    private final UniqueEquipmentPassportService passportService;

    @PostMapping("/sync")
    public Map<String, Integer> sync() {
        return Map.of("linked", service.syncFromEquipment());
    }

    @GetMapping
    public List<UniqueEquipmentService.UniqueEquipmentView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public UniqueEquipment get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/planned-works")
    public List<PlannedWork> plannedWorks(@PathVariable Long id) {
        return service.plannedWorks(id);
    }

    @PostMapping("/{id}/planned-works")
    public PlannedWork addPlannedWork(@PathVariable Long id,
                                      @RequestBody UniqueEquipmentService.PlannedWorkInput input) {
        return service.addPlannedWork(id, input);
    }

    @PatchMapping("/planned-works/{workId}")
    public PlannedWork updatePlannedWork(@PathVariable Long workId,
                                         @RequestBody UniqueEquipmentService.PlannedWorkInput input) {
        return service.updatePlannedWork(workId, input);
    }

    @DeleteMapping("/planned-works/{workId}")
    public ResponseEntity<Void> deletePlannedWork(@PathVariable Long workId) {
        service.deletePlannedWork(workId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/passport")
    public UniqueEquipment uploadPassport(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String model) {
        return passportService.upload(id, file, model);
    }

    /** Пересобрать работы из уже загруженного паспорта — в т.ч. другой моделью. */
    @PostMapping("/{id}/passport/reprocess")
    public ResponseEntity<Void> reprocessPassport(@PathVariable Long id,
                                                  @RequestParam(required = false) String model) {
        passportService.processAsync(id, model);
        return ResponseEntity.accepted().build();
    }

    /** Модели разбора паспортов, между которыми можно переключаться. */
    @GetMapping("/passport-models")
    public UniqueEquipmentPassportService.PassportModels passportModels() {
        return passportService.models();
    }
}
