package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Раздел «Расчёт плановых работ»: сметы обслуживания объектов. */
@RestController
@RequestMapping("/api/estimates")
@RequiredArgsConstructor
public class EstimateController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final EstimateService estimateService;
    private final EstimateRowRepository rowRepository;
    private final EstimateXlsxExporter exporter;

    @PostMapping
    public Estimate create(@RequestParam Long facilityId,
                           @RequestBody EstimateService.EstimateInput input) {
        return estimateService.create(facilityId, input);
    }

    @GetMapping
    public List<Estimate> list(@RequestParam(required = false) Long facilityId) {
        return estimateService.list(facilityId);
    }

    @GetMapping("/{id}")
    public EstimateService.EstimateView get(@PathVariable Long id) {
        return estimateService.view(id);
    }

    @PatchMapping("/{id}")
    public Estimate update(@PathVariable Long id, @RequestBody EstimateService.EstimateInput input) {
        return estimateService.update(id, input);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        estimateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rows")
    public EstimateRow addRow(@PathVariable Long id, @RequestBody EstimateService.RowInput input) {
        return estimateService.addRow(id, input);
    }

    @PatchMapping("/rows/{rowId}")
    public EstimateRow updateRow(@PathVariable Long rowId, @RequestBody EstimateService.RowInput input) {
        return estimateService.updateRow(rowId, input);
    }

    @DeleteMapping("/rows/{rowId}")
    public ResponseEntity<Void> deleteRow(@PathVariable Long rowId) {
        estimateService.deleteRow(rowId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<ByteArrayResource> export(@PathVariable Long id) throws Exception {
        Estimate estimate = estimateService.get(id);
        List<EstimateRow> rows = rowRepository.findByEstimateIdOrderByPosition(id);
        byte[] xlsx = exporter.export(estimate, rows);
        String filename = "Смета_" + estimate.getName().replaceAll("[^\\p{L}\\p{N}._-]", "_") + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(XLSX_MIME))
                .body(new ByteArrayResource(xlsx));
    }
}
