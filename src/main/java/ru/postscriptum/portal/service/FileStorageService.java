package ru.postscriptum.portal.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final JdbcTemplate jdbc;

    @Value("${app.upload-dir:/app/uploads}")
    private String uploadDir;

    private Path root;

    @PostConstruct
    void init() {
        try {
            root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось создать папку загрузок: " + uploadDir, e);
        }
    }

    /** Сохраняет файл на диск + регистрирует в stored_files. Возвращает {id, url, name}. */
    public Map<String, Object> store(MultipartFile file, Long userId, String purpose) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Пустой файл");
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) ext = original.substring(dot);

        String storageName = UUID.randomUUID() + ext;
        Path target = root.resolve(storageName);

        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка сохранения файла", e);
        }

        Long id = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_name, original_name, content_type, size_bytes, uploaded_by, purpose)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class,
                storageName, original, file.getContentType(), file.getSize(), userId,
                purpose != null ? purpose : "GENERAL");

        return Map.of(
                "id", id,
                "url", "/api/files/" + storageName,
                "name", original,
                "size", file.getSize()
        );
    }

    /** Читает файл с диска по storage_name. */
    public Path resolve(String storageName) {
        Path p = root.resolve(storageName).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Недопустимый путь");
        }
        return p;
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
