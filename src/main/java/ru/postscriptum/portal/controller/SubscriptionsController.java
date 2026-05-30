package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.repository.UserRepository;

import java.util.*;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionsController {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;

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
            SELECT s.id, s.lessons_used, s.lessons_total, s.start_date, s.end_date, s.status,
                   p.name AS plan_name, p.price, COALESCE(lang.code, '') AS lang, COALESCE(lang.name_ru, '') AS lang_name
            FROM subscriptions s
            JOIN subscription_plans p ON p.id = s.plan_id
            LEFT JOIN languages lang ON lang.id = p.language_id
            WHERE s.student_id = ?
            ORDER BY s.created_at DESC
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, studentId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",        row.get("id"));
            item.put("planName",  row.get("plan_name"));
            item.put("lang",      row.get("lang"));
            item.put("langName",  row.get("lang_name"));
            item.put("used",      row.get("lessons_used"));
            item.put("total",     row.get("lessons_total"));
            item.put("startDate", row.get("start_date"));
            item.put("endDate",   row.get("end_date"));
            item.put("status",    row.get("status"));
            item.put("price",     row.get("price"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/plans")
    public ResponseEntity<?> plans() {
        String sql = """
            SELECT p.id, p.name, p.lesson_count, p.price, COALESCE(lang.code,'') AS lang, COALESCE(lang.name_ru,'') AS lang_name
            FROM subscription_plans p LEFT JOIN languages lang ON lang.id = p.language_id
            WHERE p.is_active = true ORDER BY p.price
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",          row.get("id"));
            item.put("name",        row.get("name"));
            item.put("lessonCount", row.get("lesson_count"));
            item.put("price",       row.get("price"));
            item.put("lang",        row.get("lang"));
            item.put("langName",    row.get("lang_name"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }
}
