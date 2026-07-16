package ru.techdocs.document;

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

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentTypeRepository typeRepository;
    private final DocumentRepository documentRepository;

    @PostMapping("/upload")
    public List<Document> upload(@RequestParam Long facilityId,
                                 @RequestParam(required = false) Long systemId,
                                 @RequestParam(required = false) Long typeId,
                                 @RequestParam("files") List<MultipartFile> files) {
        return files.stream()
                .map(file -> documentService.upload(facilityId, systemId, typeId, file))
                .toList();
    }

    @GetMapping
    public List<Document> list(@RequestParam Long facilityId,
                               @RequestParam(required = false) Long systemId,
                               @RequestParam(required = false) Long typeId,
                               @RequestParam(required = false) String status) {
        return documentService.list(facilityId, systemId, typeId, status);
    }

    @GetMapping("/recent")
    public List<Document> recent() {
        return documentRepository.findTop10ByOrderByCreatedAtDesc();
    }

    @GetMapping("/types")
    public List<DocumentType> types() {
        return typeRepository.findAll();
    }

    @GetMapping("/{id}")
    public Document get(@PathVariable Long id) {
        return documentService.get(id);
    }

    @GetMapping("/{id}/pages")
    public List<DocumentPage> pages(@PathVariable Long id) {
        return documentService.pages(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id) {
        Document document = documentService.get(id);
        String encoded = URLEncoder.encode(document.getOriginalFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .contentType(document.getMimeType() != null
                        ? MediaType.parseMediaType(document.getMimeType())
                        : MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(documentService.download(id)));
    }

    @PostMapping("/{id}/reprocess")
    public ResponseEntity<Void> reprocess(@PathVariable Long id) {
        documentService.reprocess(id);
        return ResponseEntity.accepted().build();
    }

    @PatchMapping("/{id}")
    public Document update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        Document document = documentService.get(id);
        if (request.name() != null) document.setName(request.name());
        if (request.actualityStatus() != null) document.setActualityStatus(request.actualityStatus());
        if (request.systemId() != null) document.setEngineeringSystemId(request.systemId());
        if (request.typeId() != null) document.setDocumentTypeId(request.typeId());
        return documentRepository.save(document);
    }

    public record UpdateRequest(String name, String actualityStatus, Long systemId, Long typeId) {}

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
