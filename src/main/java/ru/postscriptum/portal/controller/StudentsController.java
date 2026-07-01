package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/students")
@RequiredArgsConstructor
public class StudentsController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public ResponseEntity<?> students(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());

        List<Map<String, Object>> result = jdbc.query(
            "SELECT u.id, u.name, u.initials, u.email, u.phone, " +
            "       p.name AS parent_name, " +
            "       (SELECT COUNT(*) FROM enrollments e WHERE e.student_id = u.id AND e.is_active) AS courses, " +
            "       (SELECT STRING_AGG(DISTINCT t.name, ', ') " +
            "          FROM enrollments e JOIN users t ON t.id = e.teacher_id " +
            "          WHERE e.student_id = u.id) AS teachers, " +
            "       (SELECT STRING_AGG(DISTINCT l.name_ru, ', ') " +
            "          FROM enrollments e JOIN languages l ON l.id = e.language_id " +
            "          WHERE e.student_id = u.id AND e.is_active) AS languages " +
            "FROM users u " +
            "LEFT JOIN student_profiles sp ON sp.user_id = u.id " +
            "LEFT JOIN users p ON p.id = sp.parent_id " +
            "WHERE u.role = 'STUDENT' AND u.is_active = true " +
            "ORDER BY u.name",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                row.put("initials", rs.getString("initials"));
                row.put("email", rs.getString("email"));
                row.put("phone", rs.getString("phone"));
                row.put("parentName", rs.getString("parent_name"));
                row.put("courses", rs.getLong("courses"));
                row.put("teachers", rs.getString("teachers"));
                row.put("languages", rs.getString("languages"));
                return row;
            }
        );

        return ResponseEntity.ok(result);
    }
}
