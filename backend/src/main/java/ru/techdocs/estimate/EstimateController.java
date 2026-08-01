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
    private final EstimateExportHistoryService exportHistory;
    private final EstimateDraftService draftService;
    private final EstimateDecisionService decisionService;
    private final ReferenceEstimateImportService referenceImportService;
    private final ru.techdocs.object.FacilityRepository facilityRepository;

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

    /** ИИ-черновик: заполнить смету строками из реестра оборудования объекта. */
    @PostMapping("/{id}/generate")
    public EstimateDraftService.DraftResult generate(@PathVariable Long id,
                                                     @RequestParam(required = false) List<Long> systemIds) {
        return draftService.generate(id, systemIds);
    }

    /** Загрузка эталонной сметы (XLSX) в память решений. */
    @PostMapping("/import-reference")
    public ReferenceEstimateImportService.ImportResult importReference(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return referenceImportService.importXlsx(file);
    }

    /** «В эталон»: сохранить строки сметы в память решений. */
    @PostMapping("/{id}/promote")
    public java.util.Map<String, Integer> promote(@PathVariable Long id) {
        return java.util.Map.of("saved", decisionService.promote(id));
    }

    /**
     * Аналог расценки, предложенный ИИ для строки. Цены и состав работ приходят
     * сразу: инженер сравнивает вариант с текущей расценкой, не выбирая его.
     */
    public record Alternative(String rateCode, String rateName, String reason, String unit,
                              java.math.BigDecimal laborCost, java.math.BigDecimal machineCost,
                              java.math.BigDecimal machineLabor, java.math.BigDecimal materialCost,
                              java.math.BigDecimal laborHours, String workComposition) {}

    public record Alternatives(List<Alternative> options, boolean aiConfigured) {}

    /**
     * Аналоги расценки для строки: ИИ подбирает по описанию оборудования и работы
     * из короткого списка найденных по каталогу расценок. Текущая расценка из
     * предложений исключается — она уже стоит в строке.
     */
    @GetMapping("/rows/{rowId}/alternatives")
    public Alternatives alternatives(@PathVariable Long rowId) {
        return estimateService.alternatives(rowId);
    }

    /** Одинаковые строки (оборудование + расценка + режим работ) — кандидаты на объединение. */
    @GetMapping("/{id}/duplicate-groups")
    public List<EstimateService.DuplicateGroup> duplicateGroups(@PathVariable Long id) {
        return estimateService.duplicateGroups(id);
    }

    public record MergeRequest(List<Long> rowIds) {}

    /** Объединяет выбранные строки в одну с суммарным количеством. */
    @PostMapping("/{id}/merge-rows")
    public EstimateRow mergeRows(@PathVariable Long id, @RequestBody MergeRequest request) {
        return estimateService.mergeRows(id, request == null ? null : request.rowIds());
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<ByteArrayResource> export(@PathVariable Long id) throws Exception {
        Estimate estimate = estimateService.get(id);
        List<EstimateRow> rows = rowRepository.findByEstimateIdOrderByPosition(id);
        java.math.BigDecimal area = facilityRepository.findById(estimate.getFacilityId())
                .map(ru.techdocs.object.Facility::getAreaSqm).orElse(null);
        // каждая выгрузка — новая версия: суффикс в имени файла + запись в «Историю версий»
        List<EstimateXlsxExporter.Version> history = exportHistory.register(id);
        byte[] xlsx = exporter.export(estimate, rows, area, history);
        String version = history.isEmpty() ? "v1" : history.get(history.size() - 1).label();
        String filename = "Смета_" + estimate.getName().replaceAll("[^\\p{L}\\p{N}._-]", "_")
                + "_" + version + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(XLSX_MIME))
                .body(new ByteArrayResource(xlsx));
    }
}
