package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.service.TeacherService;

import java.util.*;

@RestController
@RequestMapping("/api/manager")
@RequiredArgsConstructor
public class ManagerController {

    private final JdbcTemplate jdbc;
    private final TeacherService teacherService;

    @GetMapping("/teachers")
    public ResponseEntity<?> teachers(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.id, u.name, u.initials, u.avatar_url AS "avatarUrl",
                   STRING_AGG(DISTINCT l.name_ru, ', ' ORDER BY l.name_ru) AS langs,
                   STRING_AGG(DISTINCT l.code,    ','  ORDER BY l.code)    AS lang_codes
            FROM users u
            LEFT JOIN teacher_languages tl ON tl.teacher_id = u.id
            LEFT JOIN languages         l  ON l.id = tl.language_id
            WHERE u.role = 'TEACHER' AND u.is_active = true
            GROUP BY u.id, u.name, u.initials, u.avatar_url
            ORDER BY u.name
            """);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            String codes = (String) row.get("lang_codes");
            item.put("langCodes", codes != null ? List.of(codes.split(",")) : List.of());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/teacher/{teacherId}/students")
    public ResponseEntity<?> teacherStudents(@PathVariable Long teacherId, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.id, u.name, u.initials, u.email, u.avatar_url AS "avatarUrl",
                   STRING_AGG(DISTINCT l.name_ru, ', ' ORDER BY l.name_ru) AS langs,
                   STRING_AGG(DISTINCT l.code,    ','  ORDER BY l.code)    AS lang_codes
            FROM enrollments e
            JOIN users    u ON u.id = e.student_id
            JOIN languages l ON l.id = e.language_id
            WHERE e.teacher_id = ? AND e.is_active = true
            GROUP BY u.id, u.name, u.initials, u.email, u.avatar_url
            ORDER BY u.name
            """, teacherId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            String codes = (String) row.get("lang_codes");
            item.put("langCodes", codes != null ? List.of(codes.split(",")) : List.of());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/lessons")
    public ResponseEntity<?> createLesson(Authentication auth, @RequestBody Map<String, Object> body) {
        if (auth == null) return ResponseEntity.status(401).build();
        Long teacherId  = ((Number) body.get("teacherId")).longValue();
        Long studentId  = ((Number) body.get("studentId")).longValue();
        String langCode = (String) body.get("languageCode");
        String date     = (String) body.get("date");
        String time     = (String) body.get("time");
        int dur = body.get("durationMin") != null ? ((Number) body.get("durationMin")).intValue() : 60;

        Map<String, Object> enrollment;
        try {
            if (langCode != null && !langCode.isBlank()) {
                enrollment = jdbc.queryForMap("""
                    SELECT e.id, e.language_id FROM enrollments e
                    JOIN languages l ON l.id = e.language_id
                    WHERE e.teacher_id=? AND e.student_id=? AND l.code=? AND e.is_active=true
                    ORDER BY e.id DESC LIMIT 1
                    """, teacherId, studentId, langCode);
            } else {
                enrollment = jdbc.queryForMap("""
                    SELECT e.id, e.language_id FROM enrollments e
                    WHERE e.teacher_id=? AND e.student_id=? AND e.is_active=true
                    ORDER BY e.id DESC LIMIT 1
                    """, teacherId, studentId);
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Запись не найдена"));
        }

        Long langId = ((Number) enrollment.get("language_id")).longValue();
        String scheduledAt = date + " " + time + ":00";

        Long lessonId = jdbc.queryForObject("""
            INSERT INTO lessons (teacher_id, language_id, scheduled_at, duration_min, status, created_at)
            VALUES (?,?,?::timestamp,?,'PLANNED'::lesson_status,NOW()) RETURNING id
            """, Long.class, teacherId, langId, scheduledAt, dur);

        jdbc.update("INSERT INTO lesson_students (lesson_id, student_id) VALUES (?,?)", lessonId, studentId);

        return ResponseEntity.ok(Map.of("id", lessonId));
    }

    private String teacherEmailForLesson(long lessonId) {
        return jdbc.queryForObject(
            "SELECT u.email FROM lessons l JOIN users u ON u.id = l.teacher_id WHERE l.id=?",
            String.class, lessonId);
    }

    @GetMapping("/lessons/{id}/roster")
    public ResponseEntity<?> roster(@PathVariable long id) {
        return teacherService.getLessonRoster(teacherEmailForLesson(id), id);
    }

    @PostMapping("/lessons/{id}/attendance")
    public ResponseEntity<?> attendance(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return teacherService.markAttendance(teacherEmailForLesson(id), id, body);
    }

    @PostMapping("/lessons/{id}/reschedule")
    public ResponseEntity<?> reschedule(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return teacherService.rescheduleLesson(teacherEmailForLesson(id), id, body);
    }

    @PostMapping("/lessons/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return teacherService.cancelLesson(teacherEmailForLesson(id), id, body != null ? body : Map.of());
    }

    @PostMapping("/lessons/batch")
    public ResponseEntity<?> createBatchLessons(Authentication auth, @RequestBody Map<String, Object> body) {
        if (auth == null) return ResponseEntity.status(401).build();
        Long teacherId = ((Number) body.get("teacherId")).longValue();
        String langCode = (String) body.get("languageCode");
        try {
            for (long studentId : bodyStudentIds(body)) ensureEnrollment(teacherId, studentId, langCode);
        }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
        String teacherEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, teacherId);
        Map<String, Object> delegated = new HashMap<>(body);
        delegated.remove("teacherId");
        return teacherService.createBatchLessons(teacherEmail, delegated);
    }

    @PostMapping("/lessons/recurring")
    public ResponseEntity<?> createRecurringLessons(Authentication auth, @RequestBody Map<String, Object> body) {
        if (auth == null) return ResponseEntity.status(401).build();
        Long teacherId = ((Number) body.get("teacherId")).longValue();
        String langCode = (String) body.get("languageCode");
        try {
            for (long studentId : bodyStudentIds(body)) ensureEnrollment(teacherId, studentId, langCode);
        }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
        String teacherEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, teacherId);
        Map<String, Object> delegated = new HashMap<>(body);
        delegated.remove("teacherId");
        return teacherService.createRecurringLessons(teacherEmail, delegated);
    }

    /** studentIds (массив) или studentId (одиночный) из тела запроса. */
    private List<Long> bodyStudentIds(Map<String, Object> body) {
        List<Long> result = new ArrayList<>();
        if (body.get("studentIds") instanceof List<?> ids) {
            for (Object o : ids) if (o instanceof Number n) result.add(n.longValue());
        }
        if (result.isEmpty() && body.get("studentId") instanceof Number n) result.add(n.longValue());
        if (result.isEmpty()) throw new RuntimeException("Выберите хотя бы одного ученика");
        return result;
    }

    private void ensureEnrollment(long teacherId, long studentId, String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            throw new RuntimeException("Выберите язык урока");
        }
        // Check existing active enrollment
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM enrollments e
            JOIN languages l ON l.id = e.language_id
            WHERE e.teacher_id=? AND e.student_id=? AND l.code=? AND e.is_active=true
            """, Integer.class, teacherId, studentId, languageCode);
        if (count != null && count > 0) return;

        // Create enrollment automatically
        Long languageId = jdbc.queryForObject("SELECT id FROM languages WHERE code=?", Long.class, languageCode);
        if (languageId == null) throw new RuntimeException("Язык не найден: " + languageCode);

        // Re-activate if inactive enrollment already exists
        Integer existsInactive = jdbc.queryForObject("""
            SELECT COUNT(*) FROM enrollments e
            JOIN languages l ON l.id = e.language_id
            WHERE e.teacher_id=? AND e.student_id=? AND l.code=?
            """, Integer.class, teacherId, studentId, languageCode);
        if (existsInactive != null && existsInactive > 0) {
            jdbc.update("""
                UPDATE enrollments SET is_active=true
                WHERE teacher_id=? AND student_id=? AND language_id=?
                """, teacherId, studentId, languageId);
        } else {
            jdbc.update(
                "INSERT INTO enrollments (student_id, teacher_id, language_id, start_date, lessons_done, lessons_total, is_active) " +
                "VALUES (?, ?, ?, NOW()::date, 0, 0, true)",
                studentId, teacherId, languageId);
        }
    }
}
