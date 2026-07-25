package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Память эталонных решений: просмотр и удаление. */
@RestController
@RequestMapping("/api/estimate-decisions")
@RequiredArgsConstructor
public class EstimateDecisionController {

    private final EstimateDecisionService decisionService;
    private final UniqueEquipmentRepository uniqueEquipmentRepository;

    public record DecisionView(EstimateRateDecision decision, String equipmentName,
                               String model, String manufacturer, String system) {}

    @GetMapping
    public List<DecisionView> list() {
        Map<Long, UniqueEquipment> byId = new HashMap<>();
        uniqueEquipmentRepository.findAll().forEach(ue -> byId.put(ue.getId(), ue));
        return decisionService.all().stream().map(d -> {
            UniqueEquipment ue = byId.get(d.getUniqueEquipmentId());
            return new DecisionView(d,
                    ue == null ? null : ue.getName(),
                    ue == null ? null : ue.getModel(),
                    ue == null ? null : ue.getManufacturer(),
                    ue == null ? null : ue.getSystemType());
        }).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        decisionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
