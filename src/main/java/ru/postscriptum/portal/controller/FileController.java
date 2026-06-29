package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.postscriptum.portal.repository.UserRepository;
import ru.postscriptum.portal.service.FileStorageService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService storage;
    private final UserRepository userRepository;

    /** Загрузка файла (multipart). purpose: AVATAR|HOMEWORK|MATERIAL|MESSAGE */
    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "purpose", required = false) String purpose,
                                    Authentication auth) {
        Long userId = null;
        if (auth != null) {
            userId = userRepository.findByEmail(auth.getName()).map(u -> u.getId()).orElse(null);
        }
        return ResponseEntity.ok(storage.store(file, userId, purpose));
    }

    /** Отдача файла по storage_name (только для файлов, сохранённых локально до перехода на S3).
        Доступно без авторизации (ссылки в img src). Новые файлы в Timeweb S3 отдаются прямой ссылкой. */
    @GetMapping("/{name}")
    public ResponseEntity<Resource> serve(@PathVariable String name) {
        if (storage.isS3Enabled()) return ResponseEntity.notFound().build();
        try {
            Path path = storage.resolve(name);
            if (!Files.exists(path)) return ResponseEntity.notFound().build();

            Resource resource = new UrlResource(path.toUri());
            Map<String, Object> meta = storage.meta(name);
            String contentType = meta.get("content_type") != null
                    ? meta.get("content_type").toString()
                    : Files.probeContentType(path);
            if (contentType == null) contentType = "application/octet-stream";

            String original = meta.get("original_name") != null
                    ? meta.get("original_name").toString() : name;

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + original + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
