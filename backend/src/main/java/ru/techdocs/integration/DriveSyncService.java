package ru.techdocs.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.document.Document;
import ru.techdocs.document.DocumentRepository;
import ru.techdocs.document.DocumentService;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.object.Facility;
import ru.techdocs.object.FacilityRepository;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Синхронизация папки Google Drive с базой документов объекта.
 * Структура: папка объекта → подпапки = инженерные системы → файлы.
 * Файлы в корне папки объекта импортируются без привязки к системе.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DriveSyncService {

    private final DriveClient driveClient;
    private final FacilityRepository facilityRepository;
    private final EngineeringSystemRepository systemRepository;
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;

    public record SyncResult(int added, int updated, int skipped, int failed) {}

    public boolean isConfigured() {
        return driveClient.isConfigured();
    }

    @Transactional
    public SyncResult sync(Long facilityId) {
        if (!driveClient.isConfigured()) {
            throw new IllegalStateException("Google Drive не настроен (нет ключа сервисного аккаунта).");
        }
        Facility facility = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new IllegalArgumentException("Объект не найден"));
        if (facility.getDriveFolderId() == null || facility.getDriveFolderId().isBlank()) {
            throw new IllegalStateException("Для объекта не указана папка Google Drive.");
        }

        Counters c = new Counters();
        List<DriveClient.DriveEntry> root = driveClient.listChildren(facility.getDriveFolderId());

        // файлы в корне — без системы
        for (DriveClient.DriveEntry entry : root) {
            if (!entry.folder()) {
                syncFile(facility, null, entry, c);
            }
        }
        // подпапки — инженерные системы
        for (DriveClient.DriveEntry entry : root) {
            if (entry.folder()) {
                Long systemId = findOrCreateSystem(facilityId, entry.name());
                for (DriveClient.DriveEntry child : driveClient.listChildren(entry.id())) {
                    if (!child.folder()) {
                        syncFile(facility, systemId, child, c);
                    }
                }
            }
        }

        facility.setDriveSyncedAt(Instant.now());
        facilityRepository.save(facility);
        log.info("Синхронизация Drive для «{}»: +{} обновлено {} пропущено {} ошибок {}",
                facility.getName(), c.added, c.updated, c.skipped, c.failed);
        return new SyncResult(c.added, c.updated, c.skipped, c.failed);
    }

    private void syncFile(Facility facility, Long systemId, DriveClient.DriveEntry entry, Counters c) {
        if (!documentService.isSupportedExtension(entry.name())) {
            return; // неподдерживаемый формат просто игнорируем
        }
        Document existing = documentRepository.findByDriveFileId(entry.id()).orElse(null);
        if (existing != null && entry.modifiedTime().equals(existing.getDriveModifiedTime())) {
            c.skipped++; // файл не менялся
            return;
        }
        try {
            byte[] data;
            try (InputStream input = driveClient.download(entry.id())) {
                data = input.readAllBytes();
            }
            if (existing != null) {
                // файл изменился — удаляем старую версию и создаём новую
                documentRepository.delete(existing);
                c.updated++;
            } else {
                c.added++;
            }
            documentService.saveFromBytes(facility.getId(), systemId, entry.name(),
                    data, entry.mimeType(), entry.id(), entry.modifiedTime());
        } catch (Exception e) {
            c.failed++;
            log.warn("Не удалось синхронизировать файл «{}»: {}", entry.name(), e.getMessage());
        }
    }

    private Long findOrCreateSystem(Long facilityId, String name) {
        String norm = name.toLowerCase(Locale.ROOT).strip();
        for (EngineeringSystem s : systemRepository.findByFacilityIdOrderById(facilityId)) {
            if (s.getName().toLowerCase(Locale.ROOT).strip().equals(norm)) {
                return s.getId();
            }
        }
        EngineeringSystem system = new EngineeringSystem();
        system.setFacilityId(facilityId);
        system.setName(name.strip());
        return systemRepository.save(system).getId();
    }

    private static class Counters {
        int added, updated, skipped, failed;
    }
}
