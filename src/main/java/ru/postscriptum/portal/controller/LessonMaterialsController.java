package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * Материалы к занятию: файлы и ссылки, которые преподаватель прикрепляет к уроку
 * (до него или прямо во время конференции). Видят их участники занятия и родители учеников.
 */
@RestController
@RequestMapping("/api/lessons/{lessonId}/materials")
@RequiredArgsConstructor
public class LessonMaterialsController {

    private final JdbcTemplate jdbc;

    private static final int MAX_TITLE = 255;

    @GetMapping
    public ResponseEntity<?> list(@PathVariable long lessonId, Authentication auth) {
        Map<String, Object> me = me(auth);
        if (me == null) return ResponseEntity.status(401).build();
        if (!canView(lessonId, id(me), role(me))) return forbidden();

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT m.id, m.kind, m.title, m.url, m.file_size, m.created_at, m.uploaded_by, u.name AS uploaded_by_name
            FROM lesson_materials m
            LEFT JOIN users u ON u.id = m.uploaded_by
            WHERE m.lesson_id = ?
            ORDER BY m.created_at ASC, m.id ASC
            """, lessonId);

        boolean manage = canManage(lessonId, id(me), role(me));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) result.add(toDto(r, manage));
        return ResponseEntity.ok(Map.of("canManage", manage, "items", result));
    }

    /** body: { kind: FILE|LINK, title, url, size? }. FILE — url из ответа POST /api/files. */
    @PostMapping
    public ResponseEntity<?> add(@PathVariable long lessonId, @RequestBody Map<String, Object> body, Authentication auth) {
        Map<String, Object> me = me(auth);
        if (me == null) return ResponseEntity.status(401).build();
        if (!canManage(lessonId, id(me), role(me))) {
            return ResponseEntity.status(403).body(Map.of("message", "Прикреплять материалы может преподаватель занятия"));
        }

        String kind = "LINK".equals(body.get("kind")) ? "LINK" : "FILE";
        String url = body.get("url") instanceof String s ? s.trim() : "";
        String title = body.get("title") instanceof String s ? s.trim() : "";

        // только http(s)-ссылки и файлы портала: никаких javascript:/data: в href
        boolean okUrl = url.startsWith("https://") || url.startsWith("http://")
            || ("FILE".equals(kind) && url.startsWith("/api/files/"));
        if (!okUrl || url.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("message", "Некорректная ссылка"));
        }
        if (title.isEmpty()) title = "LINK".equals(kind) ? url : "Файл";
        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);
        Long size = body.get("size") instanceof Number n ? n.longValue() : null;

        Map<String, Object> row = jdbc.queryForMap("""
            INSERT INTO lesson_materials (lesson_id, uploaded_by, kind, title, url, file_size)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id, kind, title, url, file_size, created_at, uploaded_by
            """, lessonId, id(me), kind, title, url, size);
        row.put("uploaded_by_name", me.get("name"));
        return ResponseEntity.ok(toDto(row, true));
    }

    @DeleteMapping("/{materialId}")
    public ResponseEntity<?> delete(@PathVariable long lessonId, @PathVariable long materialId, Authentication auth) {
        Map<String, Object> me = me(auth);
        if (me == null) return ResponseEntity.status(401).build();
        if (!canManage(lessonId, id(me), role(me))) return forbidden();
        int n = jdbc.update("DELETE FROM lesson_materials WHERE id = ? AND lesson_id = ?", materialId, lessonId);
        return n > 0 ? ResponseEntity.ok(Map.of("deleted", materialId)) : ResponseEntity.notFound().build();
    }

    // ─── доступ ─────────────────────────────────────────────────────────────

    /** Преподаватель занятия, его ученики, их родители, администратор и менеджер. */
    private boolean canView(long lessonId, long userId, String role) {
        if (canManage(lessonId, userId, role)) return true;
        String sql = switch (role) {
            case "STUDENT" -> "SELECT COUNT(*) FROM lesson_students WHERE lesson_id = ? AND student_id = ?";
            case "PARENT"  -> "SELECT COUNT(*) FROM lesson_students ls JOIN student_profiles sp ON sp.user_id = ls.student_id "
                            + "WHERE ls.lesson_id = ? AND sp.parent_id = ?";
            default -> null;
        };
        if (sql == null) return false;
        Integer n = jdbc.queryForObject(sql, Integer.class, lessonId, userId);
        return n != null && n > 0;
    }

    /** Добавлять и удалять материалы: преподаватель занятия, администратор, менеджер. */
    private boolean canManage(long lessonId, long userId, String role) {
        Integer n;
        if ("TEACHER".equals(role)) {
            n = jdbc.queryForObject("SELECT COUNT(*) FROM lessons WHERE id = ? AND teacher_id = ?", Integer.class, lessonId, userId);
        } else if ("ADMIN".equals(role) || "MANAGER".equals(role)) {
            n = jdbc.queryForObject("SELECT COUNT(*) FROM lessons WHERE id = ?", Integer.class, lessonId);
        } else {
            return false;
        }
        return n != null && n > 0;
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private Map<String, Object> me(Authentication auth) {
        if (auth == null) return null;
        try {
            return jdbc.queryForMap("SELECT id, name, role::text AS role FROM users WHERE email = ?", auth.getName());
        } catch (Exception e) {
            return null;
        }
    }

    private static long id(Map<String, Object> me)     { return ((Number) me.get("id")).longValue(); }
    private static String role(Map<String, Object> me) { return (String) me.get("role"); }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("message", "Нет доступа к материалам этого занятия"));
    }

    private static Map<String, Object> toDto(Map<String, Object> r, boolean canDelete) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        r.get("id"));
        m.put("kind",      r.get("kind"));
        m.put("title",     r.get("title"));
        m.put("url",       r.get("url"));
        m.put("size",      r.get("file_size"));
        m.put("createdAt", r.get("created_at") instanceof Timestamp ts ? ts.toInstant().toString() : null);
        m.put("author",    r.get("uploaded_by_name"));
        m.put("canDelete", canDelete);
        return m;
    }
}
