package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.repository.UserRepository;
import ru.postscriptum.portal.service.MessageCryptoService;

import java.util.*;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessagesController {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final MessageCryptoService crypto;

    @GetMapping
    public ResponseEntity<?> conversations(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());

        String email = auth.getName();

        String sql = """
            SELECT c.id AS conv_id, u.id AS user_id, u.name, u.initials, u.avatar_url, u.role AS role,
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
            item.put("avatarUrl", row.get("avatar_url"));
            item.put("role",     row.get("role"));
            item.put("lastMsg",  crypto.decrypt((String) row.get("last_msg")));
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
            SELECT m.id, m.body, m.sent_at, m.is_read, m.is_system,
                   u.id AS sender_id, u.name AS sender_name, u.email AS sender_email, u.role AS sender_role
            FROM messages m JOIN users u ON u.id = m.sender_id
            WHERE m.conversation_id = ?
            ORDER BY m.sent_at ASC
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, convId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",          row.get("id"));
            item.put("body",        crypto.decrypt((String) row.get("body")));
            item.put("sentAt",      row.get("sent_at"));
            item.put("isRead",      row.get("is_read"));
            item.put("isSystem",    row.get("is_system"));
            item.put("senderId",    row.get("sender_id"));
            item.put("senderName",  row.get("sender_name"));
            item.put("senderEmail", row.get("sender_email"));
            item.put("senderRole",  row.get("sender_role"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    // POST /api/messages/{convId}  body {"body":"text"}  → send a message
    @PostMapping("/{convId}")
    public ResponseEntity<?> send(Authentication auth, @PathVariable Long convId, @RequestBody Map<String,String> body) {
        Long me = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, auth.getName());
        // verify membership
        Integer member = jdbc.queryForObject("SELECT COUNT(*) FROM conversation_members WHERE conversation_id=? AND user_id=?", Integer.class, convId, me);
        if (member == 0) return ResponseEntity.status(403).build();
        String plainBody = body.get("body");
        String encBody = crypto.encrypt(plainBody);
        Long id = jdbc.queryForObject(
            "INSERT INTO messages (conversation_id, sender_id, body, is_read, sent_at) VALUES (?,?,?,false,NOW()) RETURNING id",
            Long.class, convId, me, encBody);
        // notify the other member(s) — превью уведомления остаётся читаемым, само сообщение в БД зашифровано
        jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            SELECT cm.user_id, 'NEW_MESSAGE'::notification_type, ?, ?, '/messages', false, NOW()
            FROM conversation_members cm WHERE cm.conversation_id = ? AND cm.user_id != ?
            """, (Object) (jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, me)),
            plainBody, convId, me);
        // If sender is MANAGER, copy message to related conversations
        String senderRole = jdbc.queryForObject("SELECT role FROM users WHERE id=?", String.class, me);
        if ("MANAGER".equals(senderRole)) {
            // Get other members of this conversation
            List<Long> others = jdbc.queryForList(
                "SELECT user_id FROM conversation_members WHERE conversation_id=? AND user_id!=?",
                Long.class, convId, me);
            for (Long otherId : others) {
                String otherRole = jdbc.queryForObject("SELECT role FROM users WHERE id=?", String.class, otherId);
                List<Long> relatedIds = new ArrayList<>();
                if ("STUDENT".equals(otherRole)) {
                    relatedIds = jdbc.queryForList(
                        "SELECT DISTINCT teacher_id FROM enrollments WHERE student_id=? AND is_active=true",
                        Long.class, otherId);
                } else if ("TEACHER".equals(otherRole)) {
                    relatedIds = jdbc.queryForList(
                        "SELECT DISTINCT student_id FROM enrollments WHERE teacher_id=? AND is_active=true",
                        Long.class, otherId);
                }
                for (Long relId : relatedIds) {
                    // find or create conversation between otherId and relId
                    List<Long> existing = jdbc.queryForList("""
                        SELECT cm.conversation_id FROM conversation_members cm
                        WHERE cm.user_id IN (?,?)
                        GROUP BY cm.conversation_id
                        HAVING COUNT(DISTINCT cm.user_id)=2
                           AND COUNT(*)=(SELECT COUNT(*) FROM conversation_members x WHERE x.conversation_id=cm.conversation_id)
                        """, Long.class, otherId, relId);
                    Long targetConvId;
                    if (!existing.isEmpty()) {
                        targetConvId = existing.get(0);
                    } else {
                        targetConvId = jdbc.queryForObject("INSERT INTO conversations DEFAULT VALUES RETURNING id", Long.class);
                        jdbc.update("INSERT INTO conversation_members(conversation_id,user_id) VALUES(?,?),(?,?)",
                            targetConvId, otherId, targetConvId, relId);
                    }
                    if (!targetConvId.equals(convId)) {
                        jdbc.update("INSERT INTO messages(conversation_id,sender_id,body,is_read,sent_at) VALUES(?,?,?,false,NOW())",
                            targetConvId, me, encBody);
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of("id", id));
    }

    // POST /api/messages/start  body {"userId": 2}  → get-or-create 1:1 conversation, returns {conversationId}
    @PostMapping("/start")
    public ResponseEntity<?> start(Authentication auth, @RequestBody Map<String,Object> body) {
        Long me = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, auth.getName());
        Long other = ((Number) body.get("userId")).longValue();
        // find existing 1:1 conversation containing exactly these two
        List<Long> existing = jdbc.queryForList("""
            SELECT cm.conversation_id FROM conversation_members cm
            WHERE cm.user_id IN (?, ?)
            GROUP BY cm.conversation_id
            HAVING COUNT(DISTINCT cm.user_id) = 2
               AND COUNT(*) = (SELECT COUNT(*) FROM conversation_members x WHERE x.conversation_id = cm.conversation_id)
            """, Long.class, me, other);
        Long convId;
        if (!existing.isEmpty()) {
            convId = existing.get(0);
        } else {
            convId = jdbc.queryForObject("INSERT INTO conversations DEFAULT VALUES RETURNING id", Long.class);
            jdbc.update("INSERT INTO conversation_members (conversation_id, user_id) VALUES (?,?),(?,?)", convId, me, convId, other);
        }
        return ResponseEntity.ok(Map.of("conversationId", convId));
    }

    // POST /api/messages/{convId}/read  → mark all messages from others as read
    @PostMapping("/{convId}/read")
    public ResponseEntity<?> markRead(Authentication auth, @PathVariable Long convId) {
        Long me = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, auth.getName());
        jdbc.update("UPDATE messages SET is_read=true WHERE conversation_id=? AND sender_id != ?", convId, me);
        return ResponseEntity.ok().build();
    }
}
