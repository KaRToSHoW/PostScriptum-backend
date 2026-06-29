package ru.postscriptum.portal.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final JdbcTemplate jdbc;

    @Value("${app.upload-dir:/app/uploads}")
    private String uploadDir;

    @Value("${app.s3.enabled:false}")
    private boolean s3Enabled;

    @Value("${app.s3.endpoint:https://s3.twcstorage.ru}")
    private String s3Endpoint;

    @Value("${app.s3.region:ru-1}")
    private String s3Region;

    @Value("${app.s3.bucket:}")
    private String s3Bucket;

    @Value("${app.s3.access-key:}")
    private String s3AccessKey;

    @Value("${app.s3.secret-key:}")
    private String s3SecretKey;

    @Value("${app.s3.public-url:}")
    private String s3PublicUrl;

    private Path root;
    private S3Client s3Client;

    @PostConstruct
    void init() {
        if (s3Enabled) {
            s3Client = S3Client.builder()
                    .endpointOverride(URI.create(s3Endpoint))
                    .region(Region.of(s3Region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(s3AccessKey, s3SecretKey)))
                    .forcePathStyle(true)
                    .build();
            return;
        }
        try {
            root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось создать папку загрузок: " + uploadDir, e);
        }
    }

    /** Сохраняет файл (в Timeweb S3 либо на диск) + регистрирует в stored_files. Возвращает {id, url, name, size}. */
    public Map<String, Object> store(MultipartFile file, Long userId, String purpose) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Пустой файл");
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) ext = original.substring(dot);

        String bareName = UUID.randomUUID() + ext;
        String storageName = s3Enabled ? folderFor(purpose) + bareName : bareName;
        String url = s3Enabled ? storeToS3(file, storageName) : storeToDisk(file, storageName);

        Long id = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_name, original_name, content_type, size_bytes, uploaded_by, purpose)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class,
                storageName, original, file.getContentType(), file.getSize(), userId,
                purpose != null ? purpose : "GENERAL");

        return Map.of(
                "id", id,
                "url", url,
                "name", original,
                "size", file.getSize()
        );
    }

    private String storeToDisk(MultipartFile file, String storageName) {
        Path target = root.resolve(storageName);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка сохранения файла", e);
        }
        return "/api/files/" + storageName;
    }

    private String storeToS3(MultipartFile file, String storageName) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(s3Bucket)
                            .key(storageName)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new RuntimeException("Ошибка загрузки файла в Timeweb S3", e);
        }
        String base = s3PublicUrl != null && !s3PublicUrl.isBlank()
                ? s3PublicUrl
                : s3Endpoint + "/" + s3Bucket;
        return base.replaceAll("/$", "") + "/" + storageName;
    }

    /** Читает файл с диска по storage_name (только для локального режима без S3). */
    public Path resolve(String storageName) {
        Path p = root.resolve(storageName).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Недопустимый путь");
        }
        return p;
    }

    public boolean isS3Enabled() {
        return s3Enabled;
    }

    /** Папка в бакете по назначению файла: avatars/, homework/, materials/, messages/, general/. */
    private String folderFor(String purpose) {
        String p = purpose != null ? purpose.toUpperCase() : "GENERAL";
        return switch (p) {
            case "AVATAR"   -> "avatars/";
            case "HOMEWORK" -> "homework/";
            case "MATERIAL" -> "materials/";
            case "MESSAGE"  -> "messages/";
            default         -> "general/";
        };
    }

    public Map<String, Object> meta(String storageName) {
        try {
            return jdbc.queryForMap(
                    "SELECT original_name, content_type FROM stored_files WHERE storage_name = ?",
                    storageName);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
