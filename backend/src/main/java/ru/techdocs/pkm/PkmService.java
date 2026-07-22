package ru.techdocs.pkm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.NotFoundException;
import ru.techdocs.storage.FileStorage;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PkmService {

    private final PkmDocumentRepository documentRepository;
    private final PkmOperationRepository operationRepository;
    private final FileStorage fileStorage;
    private final PkmProcessingService processingService;

    public PkmDocument upload(String name, String systemType, MultipartFile file) {
        String originalFilename = file.getOriginalFilename() == null ? "regламент.docx" : file.getOriginalFilename();
        if (!extension(originalFilename).equals("docx")) {
            throw new BadRequestException("Регламент ПКМ загружается в формате DOCX. " +
                    "Формат ." + extension(originalFilename) + " не поддерживается.");
        }

        String storagePath = "pkm/" + UUID.randomUUID() + "-" + sanitize(originalFilename);
        try (InputStream input = file.getInputStream()) {
            fileStorage.save(storagePath, input, file.getSize(), file.getContentType());
        } catch (Exception e) {
            throw new BadRequestException("Не удалось сохранить файл: " + e.getMessage());
        }

        PkmDocument document = new PkmDocument();
        document.setName(isBlank(name) ? stripExtension(originalFilename) : name.strip());
        document.setSystemType(isBlank(systemType) ? null : systemType.strip());
        document.setOriginalFilename(originalFilename);
        document.setStoragePath(storagePath);
        document.setStatus(PkmDocument.STATUS_UPLOADED);
        document = documentRepository.save(document);

        processingService.processAsync(document.getId());
        return document;
    }

    public List<PkmDocument> list() {
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }

    public PkmDocument get(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Регламент не найден"));
    }

    public List<PkmOperation> operations(Long id) {
        get(id);
        return operationRepository.findByPkmIdOrderByPosition(id);
    }

    public InputStream download(Long id) {
        return fileStorage.load(get(id).getStoragePath());
    }

    public void reprocess(Long id) {
        get(id);
        processingService.processAsync(id);
    }

    public void delete(Long id) {
        PkmDocument document = get(id);
        if (document.getStoragePath() != null) {
            try {
                fileStorage.delete(document.getStoragePath());
            } catch (Exception ignored) {
                // файл мог быть удалён — операции всё равно чистятся по каскаду
            }
        }
        documentRepository.delete(document);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
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
}
