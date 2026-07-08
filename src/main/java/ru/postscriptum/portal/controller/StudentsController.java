package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
            "SELECT u.id, u.name, u.initials, u.email, u.phone, u.avatar_url, " +
            "       p.name AS parent_name, " +
            "       (SELECT COUNT(*) FROM enrollments e WHERE e.student_id = u.id AND e.is_active) AS courses, " +
            "       (SELECT STRING_AGG(DISTINCT t.name, ', ') " +
            "          FROM enrollments e JOIN users t ON t.id = e.teacher_id " +
            "          WHERE e.student_id = u.id) AS teachers, " +
            "       (SELECT STRING_AGG(DISTINCT l.code || '|' || l.name_ru, ',' " +
            "                          ORDER BY l.code || '|' || l.name_ru) " +
            "          FROM enrollments e JOIN languages l ON l.id = e.language_id " +
            "          WHERE e.student_id = u.id AND e.is_active) AS lang_pairs " +
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
                row.put("avatarUrl", rs.getString("avatar_url"));
                row.put("parentName", rs.getString("parent_name"));
                row.put("courses", rs.getLong("courses"));
                row.put("teachers", rs.getString("teachers"));
                // языки: коды и названия склеены в пары "code|name" и отсортированы одинаково —
                // langs[i] всегда соответствует langCodes[i] (флаг ↔ язык)
                List<String> langs = new ArrayList<>();
                List<String> codes = new ArrayList<>();
                String pairs = rs.getString("lang_pairs");
                if (pairs != null && !pairs.isBlank()) {
                    for (String p : pairs.split(",")) {
                        int sep = p.indexOf('|');
                        if (sep < 0) continue;
                        codes.add(p.substring(0, sep));
                        langs.add(p.substring(sep + 1));
                    }
                }
                row.put("langs", langs);
                row.put("langCodes", codes);
                row.put("languages", String.join(", ", langs));
                return row;
            }
        );

        return ResponseEntity.ok(result);
    }
}
