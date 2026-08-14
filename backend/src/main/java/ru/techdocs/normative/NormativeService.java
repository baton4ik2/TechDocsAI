package ru.techdocs.normative;

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
public class NormativeService {

    private final NormativeSourcebookRepository sourcebookRepository;
    private final NormativeRateRepository rateRepository;
    private final FileStorage fileStorage;
    private final NormativeProcessingService processingService;

    public NormativeSourcebook upload(String name, String code, MultipartFile file) {
        String originalFilename = file.getOriginalFilename() == null ? "sourcebook.pdf" : file.getOriginalFilename();
        String extension = extension(originalFilename);
        if (!extension.equals("pdf")) {
            throw new BadRequestException("Сборник загружается в формате PDF (текстовый, не скан). " +
                    "Формат ." + extension + " не поддерживается.");
        }

        String storagePath = "normatives/" + UUID.randomUUID() + "-" + sanitize(originalFilename);
        try (InputStream input = file.getInputStream()) {
            fileStorage.save(storagePath, input, file.getSize(), file.getContentType());
        } catch (Exception e) {
            throw new BadRequestException("Не удалось сохранить файл: " + e.getMessage());
        }

        NormativeSourcebook sourcebook = new NormativeSourcebook();
        sourcebook.setName(isBlank(name) ? stripExtension(originalFilename) : name.strip());
        sourcebook.setCode(isBlank(code) ? null : code.strip());
        sourcebook.setOriginalFilename(originalFilename);
        sourcebook.setStoragePath(storagePath);
        sourcebook.setStatus(NormativeSourcebook.STATUS_UPLOADED);
        sourcebook = sourcebookRepository.save(sourcebook);

        processingService.processAsync(sourcebook.getId());
        return sourcebook;
    }

    public List<NormativeSourcebook> list() {
        return sourcebookRepository.findAllByOrderByCreatedAtDesc();
    }

    public NormativeSourcebook get(Long id) {
        return sourcebookRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Сборник не найден"));
    }

    public List<NormativeRate> rates(Long id) {
        get(id);
        return rateRepository.findBySourcebookIdOrderByCode(id);
    }

    public InputStream download(Long id) {
        return fileStorage.load(get(id).getStoragePath());
    }

    /** Расценка по точному шифру — вместе с составом работ. */
    public java.util.Optional<NormativeRate> byCode(String code) {
        return isBlank(code) ? java.util.Optional.empty()
                : rateRepository.findFirstByCodeOrderById(code.strip());
    }

    public List<NormativeRate> search(String query, int limit) {
        if (isBlank(query)) return List.of();
        int capped = Math.max(1, Math.min(limit, 50));
        String q = query.strip();
        // запрос-шифр (цифры, дефисы, слэши) — ищем по коду, а не полнотекстом
        if (looksLikeCode(q)) {
            String pattern = q.replace("%", "") + "%";
            return rateRepository.searchByCode(pattern, capped);
        }
        return rateRepository.search(q, capped);
    }

    private boolean looksLikeCode(String q) {
        return q.matches("[\\d./\\-\\s]+") && q.chars().anyMatch(Character::isDigit);
    }

    /**
     * Повторный разбор PDF. Статус переводим в «обрабатывается» СРАЗУ, до постановки
     * задачи в фон: иначе список, запрошенный сразу после нажатия, ещё показывает
     * «Готов», и на экране ничего не происходит до обновления страницы.
     */
    public void reprocess(Long id) {
        NormativeSourcebook sourcebook = get(id);
        sourcebook.setStatus(NormativeSourcebook.STATUS_PROCESSING);
        sourcebook.setErrorMessage(null);
        sourcebookRepository.save(sourcebook);
        processingService.processAsync(id);
    }

    public void delete(Long id) {
        NormativeSourcebook sourcebook = get(id);
        if (sourcebook.getStoragePath() != null) {
            try {
                fileStorage.delete(sourcebook.getStoragePath());
            } catch (Exception ignored) {
                // файл мог быть уже удалён — расценки всё равно чистим по каскаду
            }
        }
        sourcebookRepository.delete(sourcebook);
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
