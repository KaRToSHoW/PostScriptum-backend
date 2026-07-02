package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public ResponseEntity<?> search(
            @RequestParam(defaultValue = "") String q,
            Authentication auth) {

        if (auth == null || q.isBlank() || q.strip().length() < 2) {
            return ResponseEntity.ok(Map.of());
        }

        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email=?", Long.class, auth.getName());
        String role = jdbc.queryForObject(
                "SELECT role FROM users WHERE id=?", String.class, userId);

        String qLike = "%" + q.strip().toLowerCase() + "%";
        Map<String, Object> result = new LinkedHashMap<>();

        // ── Ученики (для учителя / менеджера / админа) ──────────────────────
        if (List.of("TEACHER", "ADMIN", "MANAGER").contains(role)) {
            List<Map<String, Object>> students = jdbc.queryForList("""
                SELECT u.id, u.name, u.initials, u.email,
                       STRING_AGG(DISTINCT l.name_ru, ', ' ORDER BY l.name_ru) AS langs
                FROM users u
                LEFT JOIN enrollments e  ON e.student_id  = u.id AND e.is_active = true
                LEFT JOIN languages   l  ON l.id = e.language_id
                WHERE u.role = 'STUDENT' AND u.is_active = true AND LOWER(u.name) LIKE ?
                GROUP BY u.id, u.name, u.initials, u.email
                ORDER BY u.name
                LIMIT 6
                """, qLike);
            if (!students.isEmpty()) result.put("students", students);
        }

        // ── Учителя (для студента / менеджера / админа) ──────────────────────
        if (List.of("STUDENT", "ADMIN", "MANAGER").contains(role)) {
            List<Map<String, Object>> teachers = jdbc.queryForList("""
                SELECT u.id, u.name, u.initials,
                       STRING_AGG(DISTINCT l.name_ru, ', ' ORDER BY l.name_ru) AS langs
                FROM users u
                LEFT JOIN teacher_languages tl ON tl.teacher_id = u.id
                LEFT JOIN languages         l  ON l.id = tl.language_id
                WHERE u.role = 'TEACHER' AND u.is_active = true AND LOWER(u.name) LIKE ?
                GROUP BY u.id, u.name, u.initials
                ORDER BY u.name
                LIMIT 6
                """, qLike);
            if (!teachers.isEmpty()) result.put("teachers", teachers);
        }

        // ── Домашние задания ─────────────────────────────────────────────────
        if ("STUDENT".equals(role)) {
            List<Map<String, Object>> hw = jdbc.queryForList("""
                SELECT h.id, h.title, h.status,
                       COALESCE(llang.code, hlang.code, 'fr') AS lang
                FROM homework h
                LEFT JOIN lessons   l     ON l.id     = h.lesson_id
                LEFT JOIN languages llang ON llang.id = l.language_id
                LEFT JOIN languages hlang ON hlang.id = h.language_id
                WHERE h.student_id = ? AND LOWER(h.title) LIKE ?
                ORDER BY h.due_at ASC LIMIT 5
                """, userId, qLike);
            if (!hw.isEmpty()) result.put("homework", hw);
        }

        if ("TEACHER".equals(role)) {
            List<Map<String, Object>> hw = jdbc.queryForList("""
                SELECT h.id, h.title, h.status, u.name AS student,
                       COALESCE(llang.code, hlang.code, 'fr') AS lang
                FROM homework h
                JOIN  users     u     ON u.id     = h.student_id
                LEFT JOIN lessons   l     ON l.id     = h.lesson_id
                LEFT JOIN languages llang ON llang.id = l.language_id
                LEFT JOIN languages hlang ON hlang.id = h.language_id
                WHERE h.teacher_id = ? AND LOWER(h.title) LIKE ?
                ORDER BY h.due_at ASC LIMIT 5
                """, userId, qLike);
            if (!hw.isEmpty()) result.put("homework", hw);
        }

        return ResponseEntity.ok(result);
    }
}
