package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Чат с поддержкой: один персистентный диалог на пользователя, который при первом
 * обращении автоматически закрепляется за наименее загруженным менеджером.
 */
@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    private final JdbcTemplate jdbc;
    private final ru.postscriptum.portal.service.MessageCryptoService crypto;

    private static final String GREETING =
        "Здравствуйте! 👋 Я ваш персональный менеджер Post Scriptum. "
        + "Помогу выбрать программу, преподавателя и удобное расписание. "
        + "С какого языка хотите начать?";

    @PostMapping("/start")
    public ResponseEntity<?> start(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        Long me = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, auth.getName());

        Long existing = findExistingConversation(me);
        if (existing != null) {
            reassignToManagerIfNeeded(existing, me);
            return ResponseEntity.ok(Map.of("conversationId", existing));
        }

        Long managerId = pickLeastLoadedManager();
        if (managerId == null) {
            return ResponseEntity.status(503).body(Map.of("message", "Нет доступных менеджеров поддержки"));
        }

        Long convId = jdbc.queryForObject(
            "INSERT INTO conversations (support_owner_id, created_at) VALUES (?, NOW()) RETURNING id",
            Long.class, me);
        jdbc.update(
            "INSERT INTO conversation_members (conversation_id, user_id) VALUES (?,?),(?,?)",
            convId, me, convId, managerId);

        // Автоприветствие от менеджера — чтобы новый пользователь сразу видел живой чат
        jdbc.update(
            "INSERT INTO messages (conversation_id, sender_id, body, is_read, sent_at) VALUES (?,?,?,false,NOW())",
            convId, managerId, crypto.encrypt(GREETING));

        jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            SELECT ?, 'NEW_MESSAGE'::notification_type, 'Новое обращение в поддержку',
                   u.name || ' начал(а) чат с поддержкой', '/messages', false, NOW()
            FROM users u WHERE u.id = ?
            """, managerId, me);

        return ResponseEntity.ok(Map.of("conversationId", convId));
    }

    /** Если чат поддержки закреплён за админом, а активный менеджер есть — переводим на менеджера. */
    private void reassignToManagerIfNeeded(long convId, long ownerId) {
        Boolean hasManager = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM conversation_members cm
                JOIN users u ON u.id = cm.user_id
                WHERE cm.conversation_id = ? AND u.role = 'MANAGER'::user_role AND u.is_active = true
            )
            """, Boolean.class, convId);
        if (Boolean.TRUE.equals(hasManager)) return;

        Long managerId = pickLeastLoadedByRole("MANAGER");
        if (managerId == null) return;

        jdbc.update("""
            DELETE FROM conversation_members
            WHERE conversation_id = ? AND user_id != ?
            """, convId, ownerId);
        jdbc.update("INSERT INTO conversation_members (conversation_id, user_id) VALUES (?,?)", convId, managerId);
    }

    private Long findExistingConversation(long userId) {
        try {
            return jdbc.queryForObject(
                "SELECT id FROM conversations WHERE support_owner_id = ?", Long.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** Наименее загруженный менеджер; админы — только если активных менеджеров нет вовсе. */
    private Long pickLeastLoadedManager() {
        Long manager = pickLeastLoadedByRole("MANAGER");
        if (manager != null) return manager;
        return pickLeastLoadedByRole("ADMIN");
    }

    private Long pickLeastLoadedByRole(String role) {
        List<Long> candidates = jdbc.queryForList("""
            SELECT u.id
            FROM users u
            LEFT JOIN conversation_members cm ON cm.user_id = u.id
            LEFT JOIN conversations c ON c.id = cm.conversation_id AND c.support_owner_id IS NOT NULL
            WHERE u.role = ?::user_role AND u.is_active = true
            GROUP BY u.id
            ORDER BY COUNT(c.id) ASC, u.id ASC
            LIMIT 1
            """, Long.class, role);
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
