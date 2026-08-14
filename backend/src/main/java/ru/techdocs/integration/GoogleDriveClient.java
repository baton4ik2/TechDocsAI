package ru.techdocs.integration;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.techdocs.config.AppProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Реальный клиент Google Drive на сервисном аккаунте. Активируется, если задан
 * путь к JSON-ключу. Читает только папки, расшаренные на email аккаунта.
 */
@Service
@Slf4j
public class GoogleDriveClient implements DriveClient {

    private final Drive drive;

    public GoogleDriveClient(AppProperties props) {
        Drive built = null;
        String keyPath = props.drive() == null ? null : props.drive().serviceAccountKeyPath();
        if (keyPath != null && !keyPath.isBlank()) {
            try (InputStream key = Files.newInputStream(Path.of(keyPath))) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(key)
                        .createScoped(List.of(DriveScopes.DRIVE_READONLY));
                HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentials);
                built = new Drive.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        GsonFactory.getDefaultInstance(),
                        initializer)
                        .setApplicationName("TechDocs AI")
                        .build();
                log.info("Google Drive интеграция активна (сервисный аккаунт).");
            } catch (Exception e) {
                log.error("Не удалось инициализировать Google Drive: {}", e.getMessage());
            }
        }
        this.drive = built;
    }

    @Override
    public boolean isConfigured() {
        return drive != null;
    }

    @Override
    public List<DriveEntry> listChildren(String folderId) {
        if (drive == null) throw new IllegalStateException("Google Drive не настроен");
        List<DriveEntry> entries = new ArrayList<>();
        String pageToken = null;
        try {
            do {
                FileList result = drive.files().list()
                        .setQ("'" + folderId + "' in parents and trashed = false")
                        .setFields("nextPageToken, files(id, name, mimeType, modifiedTime, size)")
                        .setPageSize(200)
                        .setPageToken(pageToken)
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .execute();
                for (File f : result.getFiles()) {
                    boolean folder = DriveEntry.FOLDER_MIME.equals(f.getMimeType());
                    entries.add(new DriveEntry(
                            f.getId(), f.getName(), f.getMimeType(),
                            f.getModifiedTime() == null ? "" : f.getModifiedTime().toStringRfc3339(),
                            f.getSize() == null ? 0 : f.getSize(),
                            folder));
                }
                pageToken = result.getNextPageToken();
            } while (pageToken != null);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка чтения папки Drive: " + e.getMessage(), e);
        }
        return entries;
    }

    @Override
    public InputStream download(String fileId) {
        if (drive == null) throw new IllegalStateException("Google Drive не настроен");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            drive.files().get(fileId).setSupportsAllDrives(true).executeMediaAndDownloadTo(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка скачивания файла Drive: " + e.getMessage(), e);
        }
    }
}
