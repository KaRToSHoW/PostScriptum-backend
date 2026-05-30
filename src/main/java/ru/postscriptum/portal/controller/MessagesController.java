package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.repository.UserRepository;

import java.util.*;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessagesController {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> conversations(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());

        String email = auth.getName();

        String sql = """
            SELECT c.id AS conv_id, u.id AS user_id, u.name, u.initials,
                   (SELECT m.body FROM messages m WHERE m.conversation_id = c.id ORDER BY m.sent_at DESC LIMIT 1) AS last_msg,
                   (SELECT m.sent_at FROM messages m WHERE m.conversation_id = c.id ORDER BY m.sent_at DESC LIMIT 1) AS last_ts,
                   (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id AND m.sender_id != me.id AND m.is_read = false) AS unread
            FROM conversations c
            JOIN conversation_members cm ON cm.conversation_id = c.id
            JOIN users me ON me.email = ?
            JOIN conversation_members cm2 ON cm2.conversation_id = c.id AND cm2.user_id != me.id
            JOIN users u ON u.id = cm2.user_id
            WHERE cm.user_id = me.id
            ORDER BY last_ts DESC NULLS LAST
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, email);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",       row.get("conv_id"));
            item.put("name",     row.get("name"));
            item.put("initials", row.get("initials"));
            item.put("lastMsg",  row.get("last_msg"));
            item.put("lastTs",   row.get("last_ts"));
            item.put("unread",   row.get("unread"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{convId}")
    public ResponseEntity<?> messages(Authentication auth, @PathVariable Long convId) {
        if (auth == null) return ResponseEntity.ok(List.of());

        String sql = """
            SELECT m.id, m.body, m.sent_at, m.is_read,
                   u.id AS sender_id, u.name AS sender_name, u.email AS sender_email
            FROM messages m JOIN users u ON u.id = m.sender_id
            WHERE m.conversation_id = ?
            ORDER BY m.sent_at ASC
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, convId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",          row.get("id"));
            item.put("body",        row.get("body"));
            item.put("sentAt",      row.get("sent_at"));
            item.put("isRead",      row.get("is_read"));
            item.put("senderId",    row.get("sender_id"));
            item.put("senderName",  row.get("sender_name"));
            item.put("senderEmail", row.get("sender_email"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }
}
