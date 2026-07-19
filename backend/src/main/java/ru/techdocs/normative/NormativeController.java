package ru.techdocs.normative;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Раздел «Нормативы»: сборники СН-2012 и каталог расценок для генерации смет. */
@RestController
@RequestMapping("/api/normatives")
@RequiredArgsConstructor
public class NormativeController {

    private final NormativeService normativeService;
    private final NormativeAiMatchService aiMatchService;

    @PostMapping("/upload")
    public NormativeSourcebook upload(@RequestParam(required = false) String name,
                                      @RequestParam(required = false) String code,
                                      @RequestParam("file") MultipartFile file) {
        return normativeService.upload(name, code, file);
    }

    @GetMapping
    public List<NormativeSourcebook> list() {
        return normativeService.list();
    }

    @GetMapping("/{id}")
    public NormativeSourcebook get(@PathVariable Long id) {
        return normativeService.get(id);
    }

    @GetMapping("/{id}/rates")
    public List<NormativeRate> rates(@PathVariable Long id) {
        return normativeService.rates(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id) {
        NormativeSourcebook sourcebook = normativeService.get(id);
        String filename = sourcebook.getOriginalFilename() == null ? "sourcebook.pdf" : sourcebook.getOriginalFilename();
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(normativeService.download(id)));
    }

    @GetMapping("/rates/search")
    public List<NormativeRate> search(@RequestParam String query,
                                      @RequestParam(defaultValue = "10") int limit) {
        return normativeService.search(query, limit);
    }

    public record MatchRequest(String query) {}

    /** ИИ-подбор расценки под описание работы/оборудования (Gemini 2.5 и т.п.). */
    @PostMapping("/rates/ai-match")
    public NormativeAiMatchService.MatchResult aiMatch(@RequestBody MatchRequest request) {
        return aiMatchService.match(request == null ? null : request.query());
    }

    @GetMapping("/ai-status")
    public java.util.Map<String, Boolean> aiStatus() {
        return java.util.Map.of("aiMatchAvailable", aiMatchService.isAvailable());
    }

    @PostMapping("/{id}/reprocess")
    public ResponseEntity<Void> reprocess(@PathVariable Long id) {
        normativeService.reprocess(id);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        normativeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
