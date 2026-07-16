package ru.techdocs.storage;

import java.io.InputStream;

public interface FileStorage {

    /** Сохраняет файл и возвращает путь в хранилище. */
    String save(String objectPath, InputStream data, long size, String contentType);

    InputStream load(String objectPath);

    void delete(String objectPath);
}
