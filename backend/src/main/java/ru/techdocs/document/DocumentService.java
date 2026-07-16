package ru.techdocs.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.NotFoundException;
import ru.techdocs.processing.DocumentProcessingService;
import ru.techdocs.storage.FileStorage;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "docx", "doc", "xlsx", "xls", "txt", "csv", "jpg", "jpeg", "png");

    private final DocumentRepository documentRepository;
    private final DocumentPageRepository pageRepository;
    private final FileStorage fileStorage;
    private final DocumentProcessingService processingService;

    public Document upload(Long facilityId, Long systemId, Long typeId, MultipartFile file) {
        String originalFilename = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String extension = extension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("Формат ." + extension + " не поддерживается. " +
                    "Доступны: PDF, DOCX, XLSX, XLS, TXT, CSV, JPG, PNG.");
        }

        String storagePath = "facility-" + facilityId + "/" + UUID.randomUUID() + "-" + sanitize(originalFilename);
        try (InputStream input = file.getInputStream()) {
            fileStorage.save(storagePath, input, file.getSize(), file.getContentType());
        } catch (Exception e) {
            throw new BadRequestException("Не удалось сохранить файл: " + e.getMessage());
        }

        Document document = new Document();
        document.setFacilityId(facilityId);
        document.setEngineeringSystemId(systemId);
        document.setDocumentTypeId(typeId);
        document.setName(stripExtension(originalFilename));
        document.setOriginalFilename(originalFilename);
        document.setStoragePath(storagePath);
        document.setMimeType(file.getContentType());
        document.setSize(file.getSize());
        document.setChecksum(checksum(file));
        document.setStatus(Document.STATUS_UPLOADED);
        document = documentRepository.save(document);

        processingService.processAsync(document.getId());
        return document;
    }

    public List<Document> list(Long facilityId, Long systemId, Long typeId, String status) {
        return documentRepository.findFiltered(facilityId, systemId, typeId, status);
    }

    public Document get(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Документ не найден"));
    }

    public InputStream download(Long id) {
        return fileStorage.load(get(id).getStoragePath());
    }

    public List<DocumentPage> pages(Long id) {
        get(id);
        return pageRepository.findByDocumentIdOrderByPageNumber(id);
    }

    public void delete(Long id) {
        Document document = get(id);
        fileStorage.delete(document.getStoragePath());
        documentRepository.delete(document);
    }

    public void reprocess(Long id) {
        get(id);
        processingService.processAsync(id);
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private String sanitize(String filename) {
        return filename.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private String checksum(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
