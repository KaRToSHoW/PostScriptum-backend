package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import ru.postscriptum.portal.service.UserZoneService;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Домашние задания.
 *
 * Задание задаётся на занятие и адресовано всем ученикам этого занятия (lesson_students);
 * каждый ученик сдаёт собственное решение (homework_submissions, уникальная пара задание + ученик).
 * Преподаватель задания определяется через занятие.
 * Старые персональные задания (homework.student_id заполнен) продолжают работать:
 * их адресат — этот ученик.
 */
@RestController
@RequestMapping("/api/homework")
@RequiredArgsConstructor
public class HomeworkController {

    private final JdbcTemplate jdbc;
    private final UserZoneService zones;

    private static final DateTimeFormatter DUE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    /** Условие «ученик — адресат задания h» (для подстановки в WHERE с параметром student_id). */
    private static final String RECIPIENT = """
        (h.student_id = ? OR (h.student_id IS NULL AND EXISTS (
            SELECT 1 FROM lesson_students ls WHERE ls.lesson_id = h.lesson_id AND ls.student_id = ?)))
        """;

    /** Преподаватель задания: через занятие, для старых заданий без занятия — сохранённый в задании. */
    private static final String TEACHER_OF_HW = "COALESCE(l.teacher_id, h.teacher_id)";

    private Long userId(Authentication auth) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, auth.getName());
    }

    // ── Student: list own homework ──────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());
        Long studentId;
        try {
            studentId = userId(auth);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT h.id, h.title, h.description, h.due_at, h.attachment_url,
                   COALESCE(lang.code, hlang.code, 'fr') AS lang,
                   COALESCE(hs.status, 'ASSIGNED') AS status, hs.grade, hs.feedback
            FROM homework h
            LEFT JOIN lessons l ON l.id = h.lesson_id
            LEFT JOIN languages lang ON lang.id = l.language_id
            LEFT JOIN languages hlang ON hlang.id = h.language_id
            LEFT JOIN homework_submissions hs ON hs.homework_id = h.id AND hs.student_id = ?
            WHERE (h.student_id = ? OR (h.student_id IS NULL AND EXISTS (
                SELECT 1 FROM lesson_students ls WHERE ls.lesson_id = h.lesson_id AND ls.student_id = ?)))
            ORDER BY h.due_at ASC
            """, studentId, studentId, studentId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String due = formatDue(row.get("due_at"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",            row.get("id"));
            item.put("title",         row.get("title"));
            item.put("description",   row.get("description"));
            item.put("due",           due != null ? "до " + due : null);
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
        Long me = userId(auth);

        Integer recipient = jdbc.queryForObject(
            "SELECT COUNT(*) FROM homework h WHERE h.id = ? AND " + RECIPIENT,
            Integer.class, id, me, me);
        if (recipient == null || recipient == 0) return ResponseEntity.status(403).build();

        jdbc.update("""
            INSERT INTO homework_submissions (homework_id, student_id, text_content, file_url, status, submitted_at)
            VALUES (?, ?, ?, ?, 'SUBMITTED', NOW())
            ON CONFLICT (homework_id, student_id) DO UPDATE
            SET text_content = EXCLUDED.text_content, file_url = EXCLUDED.file_url,
                status = 'SUBMITTED', submitted_at = NOW()
            """, id, me, body.get("text"), body.get("fileUrl"));

        // для старых персональных заданий статус хранился и в самом задании
        jdbc.update("UPDATE homework SET status='SUBMITTED'::homework_status WHERE id=? AND student_id=?", id, me);

        String studentName = jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, me);
        jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            SELECT COALESCE(l.teacher_id, h.teacher_id), 'SYSTEM'::notification_type, 'Сдано домашнее задание', ? || ': ' || h.title, '/homework', false, NOW()
            FROM homework h LEFT JOIN lessons l ON l.id = h.lesson_id
            WHERE h.id = ?
            """, studentName, id);

        return ResponseEntity.ok().build();
    }

    // ── Teacher: lessons to which homework can be assigned ─────────────────
    @GetMapping("/lessons")
    public ResponseEntity<?> teacherLessons(Authentication auth) {
        Long me = userId(auth);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT l.id, l.scheduled_at, l.format::text AS format, lang.code AS lang, lang.name_ru AS language,
                   STRING_AGG(u.name, ', ' ORDER BY u.name) AS students
            FROM lessons l
            JOIN languages lang ON lang.id = l.language_id
            LEFT JOIN lesson_students ls ON ls.lesson_id = l.id
            LEFT JOIN users u ON u.id = ls.student_id
            WHERE l.teacher_id = ? AND l.status <> 'CANCELLED'::lesson_status
              AND l.scheduled_at > NOW() - INTERVAL '30 days'
              AND l.scheduled_at < NOW() + INTERVAL '14 days'
            GROUP BY l.id, l.scheduled_at, l.format, lang.code, lang.name_ru
            ORDER BY l.scheduled_at DESC
            """, me);
        return ResponseEntity.ok(rows);
    }

    // ── Teacher: review queue (одна строка на пару «задание + ученик») ──────
    @GetMapping("/teacher")
    public ResponseEntity<?> teacherList(Authentication auth) {
        Long me = userId(auth);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT h.id, h.title, h.description, h.due_at, h.attachment_url,
                   s.id AS student_id, s.name AS student, s.initials AS student_initials, s.avatar_url AS student_avatar,
                   COALESCE(llang.code, hlang.code, 'fr') AS lang,
                   hs.text_content, hs.file_url, hs.submitted_at, hs.grade, hs.feedback,
                   COALESCE(hs.status, 'ASSIGNED') AS status
            FROM homework h
            LEFT JOIN lessons l ON l.id = h.lesson_id
            JOIN LATERAL (
                SELECT h.student_id AS sid WHERE h.student_id IS NOT NULL
                UNION
                SELECT ls.student_id FROM lesson_students ls
                WHERE h.student_id IS NULL AND ls.lesson_id = h.lesson_id
            ) r ON true
            JOIN users s ON s.id = r.sid
            LEFT JOIN languages llang ON llang.id = l.language_id
            LEFT JOIN languages hlang ON hlang.id = h.language_id
            LEFT JOIN homework_submissions hs ON hs.homework_id = h.id AND hs.student_id = s.id
            WHERE COALESCE(l.teacher_id, h.teacher_id) = ?
            ORDER BY s.name ASC, (COALESCE(hs.status, '') = 'SUBMITTED') DESC, h.due_at ASC
            """, me);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String submittedAt = null;
            if (row.get("submitted_at") instanceof java.sql.Timestamp ts) {
                submittedAt = ts.toInstant().toString();
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",              row.get("id"));
            item.put("title",           row.get("title"));
            item.put("description",     row.get("description"));
            item.put("status",          row.get("status"));
            item.put("due",             formatDue(row.get("due_at")));
            item.put("studentId",       row.get("student_id"));
            item.put("student",         row.get("student"));
            item.put("studentInitials", row.get("student_initials"));
            item.put("studentAvatar",   row.get("student_avatar"));
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

    // ── Teacher: grade one student's submission ─────────────────────────────
    /** body: { studentId, grade, feedback } */
    @PostMapping("/{id}/review")
    public ResponseEntity<?> review(Authentication auth,
                                    @PathVariable Long id,
                                    @RequestBody Map<String, Object> body) {
        Long me = userId(auth);

        Map<String, Object> hw;
        try {
            hw = jdbc.queryForMap(
                "SELECT h.student_id, " + TEACHER_OF_HW + " AS teacher_id FROM homework h "
                    + "LEFT JOIN lessons l ON l.id = h.lesson_id WHERE h.id = ?", id);
        } catch (Exception e) {
            return ResponseEntity.status(404).build();
        }
        if (hw.get("teacher_id") == null || ((Number) hw.get("teacher_id")).longValue() != me) {
            return ResponseEntity.status(403).build();
        }

        Long studentId = body.get("studentId") instanceof Number n ? n.longValue()
            : hw.get("student_id") != null ? ((Number) hw.get("student_id")).longValue() : null;
        if (studentId == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Не указан ученик"));
        }

        Integer grade = body.get("grade") != null ? ((Number) body.get("grade")).intValue() : null;
        String feedback = (String) body.get("feedback");

        int updated = jdbc.update("""
            UPDATE homework_submissions SET grade=?, feedback=?, status='REVIEWED', reviewed_at=NOW()
            WHERE homework_id=? AND student_id=?
            """, grade, feedback, id, studentId);
        if (updated == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Ученик ещё не сдал это задание"));
        }
        jdbc.update("UPDATE homework SET status='REVIEWED'::homework_status WHERE id=? AND student_id=?", id, studentId);

        jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            SELECT ?, 'SYSTEM'::notification_type, 'Задание проверено', h.title, '/homework', false, NOW()
            FROM homework h WHERE h.id = ?
            """, studentId, id);

        return ResponseEntity.ok().build();
    }

    // ── Teacher: create assignment for a lesson ─────────────────────────────
    /** body: { lessonId, title, description?, dueAt?, attachmentUrl? } — задание получают все ученики занятия. */
    @PostMapping
    public ResponseEntity<?> create(Authentication auth, @RequestBody Map<String, Object> body) {
        Long me = userId(auth);

        if (!(body.get("lessonId") instanceof Number lessonNum)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Выберите занятие, к которому относится задание"));
        }
        long lessonId = lessonNum.longValue();

        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM lessons WHERE id=? AND teacher_id=?", Integer.class, lessonId, me);
        if (owns == null || owns == 0) {
            return ResponseEntity.status(403).body(Map.of("message", "Это не ваше занятие"));
        }

        java.sql.Timestamp due = null;
        String dueStr = (String) body.get("dueAt");
        if (dueStr != null && !dueStr.isBlank()) {
            // срок вводится по времени преподавателя (его часовой пояс из профиля), а не сервера
            String normalized = dueStr.length() <= 10 ? dueStr + "T23:59:00" : dueStr.replace(" ", "T");
            due = java.sql.Timestamp.from(java.time.LocalDateTime.parse(normalized).atZone(zones.current()).toInstant());
        }

        // teacher_id и language_id заполняются для совместимости со старыми отчётами;
        // по модели данных преподаватель и язык определяются через занятие
        Long newId = jdbc.queryForObject("""
            INSERT INTO homework (lesson_id, teacher_id, student_id, title, description, due_at, attachment_url, language_id, status, created_at)
            SELECT l.id, l.teacher_id, NULL, ?, ?, ?, ?, l.language_id, 'ASSIGNED'::homework_status, NOW()
            FROM lessons l WHERE l.id = ?
            RETURNING id
            """, Long.class,
            body.get("title"), body.get("description"), due, body.get("attachmentUrl"), lessonId);

        int recipients = jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            SELECT ls.student_id, 'HOMEWORK_DUE'::notification_type, 'Новое домашнее задание', ?, '/homework', false, NOW()
            FROM lesson_students ls WHERE ls.lesson_id = ?
            """, body.get("title"), lessonId);

        return ResponseEntity.ok(Map.of("id", newId, "recipients", recipients));
    }

    private String formatDue(Object dueAt) {
        if (dueAt instanceof java.sql.Timestamp ts) return ts.toInstant().atZone(zones.current()).format(DUE_FMT);
        if (dueAt instanceof java.sql.Date d) return d.toLocalDate().format(DUE_FMT);
        return null;
    }
}
