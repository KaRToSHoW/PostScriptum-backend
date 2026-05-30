package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());

        String email = auth.getName();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, type, title, body, link, is_read, created_at FROM notifications " +
            "WHERE user_id = (SELECT id FROM users WHERE email=?) " +
            "ORDER BY created_at DESC LIMIT 50",
            email);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",          row.get("id"));
            item.put("type",        row.get("type"));
            item.put("title",       row.get("title"));
            item.put("body",        row.get("body"));
            item.put("link",        row.get("link"));
            item.put("isRead",      row.get("is_read"));
            item.put("createdAt",   row.get("created_at"));
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(Map.of("count", 0));

        String email = auth.getName();
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = (SELECT id FROM users WHERE email=?) AND is_read = false",
            Integer.class, email);
        return ResponseEntity.ok(Map.of("count", count == null ? 0 : count));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<?> markOne(Authentication auth, @PathVariable Long id) {
        if (auth == null) return ResponseEntity.status(401).build();

        Long me = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, auth.getName());
        jdbc.update("UPDATE notifications SET is_read=true WHERE id=? AND user_id=?", id, me);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> markAll(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        Long me = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, auth.getName());
        jdbc.update("UPDATE notifications SET is_read=true WHERE user_id=? AND is_read=false", me);
        return ResponseEntity.ok().build();
    }
}
