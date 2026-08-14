package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.document.Document;
import ru.techdocs.document.DocumentRepository;
import ru.techdocs.document.DocumentService;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.integration.DriveClient;
import ru.techdocs.integration.DriveSyncService;
import ru.techdocs.object.Facility;
import ru.techdocs.object.FacilityRepository;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DriveSyncServiceTest {

    /** Фейковый Drive: дерево папок в памяти. */
    static class FakeDrive implements DriveClient {
        final Map<String, List<DriveEntry>> children = new HashMap<>();
        public boolean isConfigured() { return true; }
        public List<DriveEntry> listChildren(String folderId) {
            return children.getOrDefault(folderId, List.of());
        }
        public InputStream download(String fileId) {
            return new ByteArrayInputStream(("data-" + fileId).getBytes());
        }
    }

    @Test
    void syncMapsSubfoldersToSystemsAndDedupes() {
        FakeDrive drive = new FakeDrive();
        // корень: файл в корне + подпапка АПС
        drive.children.put("ROOT", List.of(
                new DriveClient.DriveEntry("f-root", "Общий паспорт.pdf", "application/pdf", "2026-01-01T00:00:00Z", 100, false),
                new DriveClient.DriveEntry("dir-aps", "АПС", DriveClient.DriveEntry.FOLDER_MIME, "", 0, true)
        ));
        // внутри АПС: новый файл + уже синхронизированный (не менялся) + изменённый
        drive.children.put("dir-aps", List.of(
                new DriveClient.DriveEntry("f-new", "Спецификация.xlsx", "app/xlsx", "2026-02-01T00:00:00Z", 200, false),
                new DriveClient.DriveEntry("f-same", "Проект.pdf", "application/pdf", "2026-01-10T00:00:00Z", 300, false),
                new DriveClient.DriveEntry("f-changed", "Схема.pdf", "application/pdf", "2026-03-01T00:00:00Z", 400, false)
        ));

        var facilityRepo = mock(FacilityRepository.class);
        var systemRepo = mock(EngineeringSystemRepository.class);
        var docRepo = mock(DocumentRepository.class);
        var docService = mock(DocumentService.class);

        Facility facility = new Facility();
        facility.setId(1L);
        facility.setName("ЖК Лето");
        facility.setDriveFolderId("ROOT");
        when(facilityRepo.findById(1L)).thenReturn(Optional.of(facility));

        // системы объекта: АПС уже существует с id=10
        EngineeringSystem aps = new EngineeringSystem();
        aps.setId(10L); aps.setFacilityId(1L); aps.setName("АПС");
        when(systemRepo.findByFacilityIdOrderById(1L)).thenReturn(List.of(aps));

        // все форматы поддерживаются
        when(docService.isSupportedExtension(anyString())).thenReturn(true);

        // f-same уже синхронизирован с тем же временем → пропуск;
        // f-changed синхронизирован, но со старым временем → обновление
        when(docRepo.findByDriveFileId(anyString())).thenReturn(Optional.empty());
        Document same = new Document();
        same.setDriveFileId("f-same");
        same.setDriveModifiedTime("2026-01-10T00:00:00Z");
        when(docRepo.findByDriveFileId("f-same")).thenReturn(Optional.of(same));
        Document changed = new Document();
        changed.setDriveFileId("f-changed");
        changed.setDriveModifiedTime("2026-02-15T00:00:00Z"); // старее, чем в Drive
        when(docRepo.findByDriveFileId("f-changed")).thenReturn(Optional.of(changed));

        var service = new DriveSyncService(drive, facilityRepo, systemRepo, docRepo, docService);
        DriveSyncService.SyncResult result = service.sync(1L);

        // f-root (новый, без системы) + f-new (новый в АПС) = 2 добавлено
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(1);   // f-changed
        assertThat(result.skipped()).isEqualTo(1);   // f-same
        assertThat(result.failed()).isEqualTo(0);

        // файл в корне сохранён без системы (systemId = null)
        verify(docService).saveFromBytes(eq(1L), isNull(), eq("Общий паспорт.pdf"),
                any(), any(), eq("f-root"), any());
        // файл из подпапки АПС сохранён с systemId = 10
        verify(docService).saveFromBytes(eq(1L), eq(10L), eq("Спецификация.xlsx"),
                any(), any(), eq("f-new"), any());
        // изменённый файл: старая версия удалена
        verify(docRepo).delete(changed);
        // время синхронизации проставлено
        assertThat(facility.getDriveSyncedAt()).isNotNull();
    }
}
