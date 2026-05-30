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
            SELECT h.id, h.title, h.due_at, h.status, COALESCE(lang.code, 'fr') AS lang,
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
            item.put("id",       row.get("id"));
            item.put("title",    row.get("title"));
            item.put("due",      due);
            item.put("status",   row.get("status"));
            item.put("lang",     row.get("lang"));
            item.put("grade",    row.get("grade"));
            item.put("feedback", row.get("feedback"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }
}
