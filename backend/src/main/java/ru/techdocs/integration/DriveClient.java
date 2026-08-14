package ru.techdocs.integration;

import java.io.InputStream;
import java.util.List;

/**
 * Абстракция доступа к облачному хранилищу папок (Google Drive).
 * Позволяет подменять реальный клиент фейковым в тестах.
 */
public interface DriveClient {

    boolean isConfigured();

    /** Файл или подпапка в Drive. */
    record DriveEntry(String id, String name, String mimeType, String modifiedTime,
                      long size, boolean folder) {
        public static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    }

    /** Содержимое папки (файлы и подпапки, без рекурсии). */
    List<DriveEntry> listChildren(String folderId);

    InputStream download(String fileId);
}
