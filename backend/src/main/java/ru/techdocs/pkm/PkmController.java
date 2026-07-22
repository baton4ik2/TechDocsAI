package ru.techdocs.pkm;

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

/** Раздел «ПКМ»: регламенты обязательных работ по типам инженерных систем. */
@RestController
@RequestMapping("/api/pkm")
@RequiredArgsConstructor
public class PkmController {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final PkmService pkmService;

    @PostMapping("/upload")
    public PkmDocument upload(@RequestParam(required = false) String name,
                              @RequestParam(required = false) String systemType,
                              @RequestParam("file") MultipartFile file) {
        return pkmService.upload(name, systemType, file);
    }

    @GetMapping
    public List<PkmDocument> list() {
        return pkmService.list();
    }

    @GetMapping("/{id}")
    public PkmDocument get(@PathVariable Long id) {
        return pkmService.get(id);
    }

    @GetMapping("/{id}/operations")
    public List<PkmOperation> operations(@PathVariable Long id) {
        return pkmService.operations(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id) {
        PkmDocument document = pkmService.get(id);
        String filename = document.getOriginalFilename() == null ? "regламент.docx" : document.getOriginalFilename();
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(DOCX_MIME))
                .body(new InputStreamResource(pkmService.download(id)));
    }

    @PostMapping("/{id}/reprocess")
    public ResponseEntity<Void> reprocess(@PathVariable Long id) {
        pkmService.reprocess(id);
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        pkmService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
