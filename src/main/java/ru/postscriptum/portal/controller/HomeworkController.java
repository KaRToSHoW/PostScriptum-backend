package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.repository.UserRepository;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/homework")
@RequiredArgsConstructor
public class HomeworkController {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DUE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    // ── Student: list own homework ──────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());

        String email = auth.getName();
        Long studentId;
        try {
            studentId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }

        String sql = """
            SELECT h.id, h.title, h.description, h.due_at, h.status, h.attachment_url,
                   COALESCE(lang.code, 'fr') AS lang,
                   hs.grade, hs.feedback
            FROM homework h
            LEFT JOIN lessons l ON l.id = h.lesson_id
            LEFT JOIN languages lang ON lang.id = l.language_id
            LEFT JOIN homework_submissions hs ON hs.homework_id = h.id
            WHERE h.student_id = ?
            ORDER BY h.due_at ASC
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, studentId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String due = null;
            Object dueAt = row.get("due_at");
            if (dueAt instanceof java.sql.Timestamp ts) {
                String formatted = ts.toInstant().atZone(MOSCOW).format(DUE_FMT);
                due = "до " + formatted;
            } else if (dueAt instanceof java.sql.Date d) {
                String formatted = d.toLocalDate().format(DUE_FMT);
                due = "до " + formatted;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",            row.get("id"));
            item.put("title",         row.get("title"));
            item.put("description",   row.get("description"));
            item.put("due",           due);
            item.put("status",        row.get("status"));
            item.put("lang",          row.get("lang"));
            item.put("grade",         row.get("grade"));
            item.put("feedback",      row.get("feedback"));
            item.put("attachmentUrl", row.get("attachment_url"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    // ── Student: submit work ────────────────────────────────────────────────
    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submit(Authentication auth,
                                    @PathVariable Long id,
                                    @RequestBody Map<String, String> body) {
        Long me = jdbc.queryForObject(
            "SELECT id FROM users WHERE email=?", Long.class, auth.getName());

        Integer own = jdbc.queryForObject(
            "SELECT COUNT(*) FROM homework WHERE id=? AND student_id=?",
            Integer.class, id, me);
        if (own == null || own == 0) return ResponseEntity.status(403).build();

        Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM homework_submissions WHERE homework_id=?",
            Integer.class, id);
        if (exists != null && exists > 0) {
            jdbc.update(
                "UPDATE homework_submissions SET text_content=?, file_url=?, submitted_at=NOW() WHERE homework_id=?",
                body.get("text"), body.get("fileUrl"), id);
        } else {
            jdbc.update(
                "INSERT INTO homework_submissions (homework_id, text_content, file_url, submitted_at) VALUES (?,?,?,NOW())",
                id, body.get("text"), body.get("fileUrl"));
        }

        jdbc.update("UPDATE homework SET status='SUBMITTED'::homework_status WHERE id=?", id);

        jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            SELECT h.teacher_id, 'SYSTEM'::notification_type, 'Сдано домашнее задание', h.title, '/homework', false, NOW()
            FROM homework h WHERE h.id = ?
            """, id);

        return ResponseEntity.ok().build();
    }

    // ── Teacher: review queue ───────────────────────────────────────────────
    @GetMapping("/teacher")
    public ResponseEntity<?> teacherList(Authentication auth) {
        Long me = jdbc.queryForObject(
            "SELECT id FROM users WHERE email=?", Long.class, auth.getName());

        String sql = """
            SELECT h.id, h.title, h.description, h.status, h.due_at, h.attachment_url,
                   s.id AS student_id, s.name AS student, s.initials AS student_initials,
                   COALESCE(lang.code,'fr') AS lang,
                   hs.text_content, hs.file_url, hs.submitted_at, hs.grade, hs.feedback
            FROM homework h
            JOIN users s ON s.id = h.student_id
            LEFT JOIN lessons l ON l.id = h.lesson_id
            LEFT JOIN languages lang ON lang.id = l.language_id
            LEFT JOIN homework_submissions hs ON hs.homework_id = h.id
            WHERE h.teacher_id = ?
            ORDER BY s.name ASC, (h.status='SUBMITTED'::homework_status) DESC, h.due_at ASC
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, me);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            // format due_at → "dd.MM"
            String due = null;
            Object dueAt = row.get("due_at");
            if (dueAt instanceof java.sql.Timestamp ts) {
                due = ts.toInstant().atZone(MOSCOW).format(DUE_FMT);
            } else if (dueAt instanceof java.sql.Date d) {
                due = d.toLocalDate().format(DUE_FMT);
            }

            // format submittedAt as ISO string or null
            String submittedAt = null;
            Object subAt = row.get("submitted_at");
            if (subAt instanceof java.sql.Timestamp ts2) {
                submittedAt = ts2.toInstant().toString();
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",              row.get("id"));
            item.put("title",           row.get("title"));
            item.put("description",     row.get("description"));
            item.put("status",          row.get("status"));
            item.put("due",             due);
            item.put("studentId",       row.get("student_id"));
            item.put("student",         row.get("student"));
            item.put("studentInitials", row.get("student_initials"));
            item.put("lang",            row.get("lang"));
            item.put("text",            row.get("text_content"));
            item.put("fileUrl",         row.get("file_url"));
            item.put("attachmentUrl",   row.get("attachment_url"));
            item.put("submittedAt",     submittedAt);
            item.put("grade",           row.get("grade"));
            item.put("feedback",        row.get("feedback"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    // ── Teacher: grade submission ───────────────────────────────────────────
    @PostMapping("/{id}/review")
    public ResponseEntity<?> review(Authentication auth,
                                    @PathVariable Long id,
                                    @RequestBody Map<String, Object> body) {
        Long me = jdbc.queryForObject(
            "SELECT id FROM users WHERE email=?", Long.class, auth.getName());

        Integer own = jdbc.queryForObject(
            "SELECT COUNT(*) FROM homework WHERE id=? AND teacher_id=?",
            Integer.class, id, me);
        if (own == null || own == 0) return ResponseEntity.status(403).build();

        Integer grade = body.get("grade") != null
            ? ((Number) body.get("grade")).intValue()
            : null;
        String feedback = (String) body.get("feedback");

        jdbc.update(
            "UPDATE homework_submissions SET grade=?, feedback=?, reviewed_at=NOW(), reviewed_by=? WHERE homework_id=?",
            grade, feedback, me, id);
        jdbc.update("UPDATE homework SET status='REVIEWED'::homework_status WHERE id=?", id);

        jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            SELECT h.student_id, 'SYSTEM'::notification_type, 'Задание проверено', h.title, '/homework', false, NOW()
            FROM homework h WHERE h.id = ?
            """, id);

        return ResponseEntity.ok().build();
    }

    // ── Teacher: create assignment ──────────────────────────────────────────
    @PostMapping
    public ResponseEntity<?> create(Authentication auth,
                                    @RequestBody Map<String, Object> body) {
        Long me = jdbc.queryForObject(
            "SELECT id FROM users WHERE email=?", Long.class, auth.getName());

        Long studentId = ((Number) body.get("studentId")).longValue();
        Object lessonIdRaw = body.get("lessonId");
        Long lessonId = lessonIdRaw != null ? ((Number) lessonIdRaw).longValue() : null;

        String dueStr = (String) body.get("dueAt");
        java.sql.Timestamp due = null;
        if (dueStr != null) {
            String normalized = dueStr.length() <= 10
                ? dueStr + " 23:59:00"
                : dueStr.replace("T", " ");
            due = java.sql.Timestamp.valueOf(normalized);
        }

        Long newId = jdbc.queryForObject("""
            INSERT INTO homework (lesson_id, teacher_id, student_id, title, description, due_at, attachment_url, status, created_at)
            VALUES (?,?,?,?,?,?,?,'ASSIGNED'::homework_status, NOW()) RETURNING id
            """, Long.class,
            lessonId, me, studentId,
            body.get("title"), body.get("description"), due, body.get("attachmentUrl"));

        jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            VALUES (?, 'HOMEWORK_DUE'::notification_type, 'Новое домашнее задание', ?, '/homework', false, NOW())
            """, studentId, body.get("title"));

        return ResponseEntity.ok(Map.of("id", newId));
    }
}
