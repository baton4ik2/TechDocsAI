package ru.techdocs.integration;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.NotFoundException;
import ru.techdocs.object.Facility;
import ru.techdocs.object.FacilityRepository;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/facilities/{facilityId}/drive")
@RequiredArgsConstructor
public class DriveController {

    // ссылка вида https://drive.google.com/drive/folders/<ID>?...
    private static final Pattern FOLDER_LINK = Pattern.compile("/folders/([A-Za-z0-9_-]+)");

    private final FacilityRepository facilityRepository;
    private final DriveSyncService syncService;

    public record DriveStatus(boolean available, String folderId, Instant syncedAt) {}

    @GetMapping
    public DriveStatus status(@PathVariable Long facilityId) {
        Facility facility = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new NotFoundException("Объект не найден"));
        return new DriveStatus(syncService.isConfigured(),
                facility.getDriveFolderId(), facility.getDriveSyncedAt());
    }

    public record FolderRequest(String folder) {}

    /** Принимает id папки или полную ссылку на папку Google Drive. */
    @PutMapping("/folder")
    public DriveStatus setFolder(@PathVariable Long facilityId, @RequestBody FolderRequest request) {
        Facility facility = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new NotFoundException("Объект не найден"));
        facility.setDriveFolderId(extractFolderId(request.folder()));
        facilityRepository.save(facility);
        return new DriveStatus(syncService.isConfigured(),
                facility.getDriveFolderId(), facility.getDriveSyncedAt());
    }

    @PostMapping("/sync")
    public DriveSyncService.SyncResult sync(@PathVariable Long facilityId) {
        try {
            return syncService.sync(facilityId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private String extractFolderId(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.strip();
        Matcher m = FOLDER_LINK.matcher(trimmed);
        if (m.find()) return m.group(1);
        return trimmed; // считаем, что передали чистый id
    }
}
