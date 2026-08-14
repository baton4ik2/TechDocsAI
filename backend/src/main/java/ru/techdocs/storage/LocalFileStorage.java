package ru.techdocs.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@ConditionalOnProperty(name = "techdocs.storage.type", havingValue = "file")
@Slf4j
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${techdocs.storage.local-path:./data/documents}") String localPath) {
        this.root = Path.of(localPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new StorageException("Не удалось создать папку хранилища: " + root, e);
        }
        log.info("Локальное хранилище файлов: {}", root);
    }

    private Path resolve(String objectPath) {
        Path path = root.resolve(objectPath).normalize();
        if (!path.startsWith(root)) {
            throw new StorageException("Недопустимый путь файла: " + objectPath, null);
        }
        return path;
    }

    @Override
    public String save(String objectPath, InputStream data, long size, String contentType) {
        try {
            Path target = resolve(objectPath);
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
            return objectPath;
        } catch (IOException e) {
            throw new StorageException("Не удалось сохранить файл: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream load(String objectPath) {
        try {
            return Files.newInputStream(resolve(objectPath));
        } catch (IOException e) {
            throw new StorageException("Не удалось прочитать файл: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String objectPath) {
        try {
            Files.deleteIfExists(resolve(objectPath));
        } catch (IOException e) {
            log.warn("Не удалось удалить файл {}: {}", objectPath, e.getMessage());
        }
    }
}
