package ru.techdocs.storage;

import io.minio.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.techdocs.config.AppProperties;

import java.io.InputStream;

@Service
@ConditionalOnProperty(name = "techdocs.storage.type", havingValue = "minio", matchIfMissing = true)
@Slf4j
public class MinioFileStorage implements FileStorage {

    private final MinioClient client;
    private final String bucket;

    public MinioFileStorage(AppProperties props) {
        this.client = MinioClient.builder()
                .endpoint(props.storage().endpoint())
                .credentials(props.storage().accessKey(), props.storage().secretKey())
                .build();
        this.bucket = props.storage().bucket();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Создан бакет MinIO: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("Не удалось проверить/создать бакет MinIO ({}). Он будет создан при первой загрузке.",
                    e.getMessage());
        }
    }

    @Override
    public String save(String objectPath, InputStream data, long size, String contentType) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .stream(data, size, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
            return objectPath;
        } catch (Exception e) {
            throw new StorageException("Не удалось сохранить файл в хранилище: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream load(String objectPath) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectPath).build());
        } catch (Exception e) {
            throw new StorageException("Не удалось прочитать файл из хранилища: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String objectPath) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectPath).build());
        } catch (Exception e) {
            log.warn("Не удалось удалить файл {}: {}", objectPath, e.getMessage());
        }
    }
}
