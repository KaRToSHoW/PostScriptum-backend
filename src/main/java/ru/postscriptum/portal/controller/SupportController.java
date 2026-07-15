package ru.postscriptum.portal.controller;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
@Slf4j
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

    /**
     * Схлопывает задвоившиеся диалоги поддержки (их плодил балансер, пока не было
     * уникального индекса) и ставит уникальный индекс, чтобы больше не плодились.
     * Идемпотентно — безопасно выполнять на каждом старте инстанса.
     */
    @PostConstruct
    void dedupeSupportConversations() {
        try {
            dedupeAndIndex();
        } catch (Exception e) {
            // не валим старт приложения: findExistingConversation терпим к дублям и без индекса
            log.warn("Не удалось схлопнуть дубли диалогов поддержки: {}", e.getMessage());
        }
    }

    private void dedupeAndIndex() {
        // 1) сообщения дублей переносим на самый ранний диалог владельца
        jdbc.update("""
            UPDATE messages msg SET conversation_id = k.keep_id
            FROM (SELECT id, MIN(id) OVER (PARTITION BY support_owner_id) AS keep_id
                  FROM conversations WHERE support_owner_id IS NOT NULL) k
            WHERE msg.conversation_id = k.id AND k.id <> k.keep_id
            """);
        // 2) участников переносим без дублей
        jdbc.update("""
            INSERT INTO conversation_members (conversation_id, user_id)
            SELECT DISTINCT k.keep_id, cm.user_id
            FROM conversation_members cm
            JOIN (SELECT id, MIN(id) OVER (PARTITION BY support_owner_id) AS keep_id
                  FROM conversations WHERE support_owner_id IS NOT NULL) k
              ON k.id = cm.conversation_id AND k.id <> k.keep_id
            WHERE NOT EXISTS (SELECT 1 FROM conversation_members x
                              WHERE x.conversation_id = k.keep_id AND x.user_id = cm.user_id)
            """);
        // 3) удаляем дубли (их сообщения/участники уже перенесены)
        jdbc.update("""
            DELETE FROM conversations c
            USING (SELECT id, MIN(id) OVER (PARTITION BY support_owner_id) AS keep_id
                   FROM conversations WHERE support_owner_id IS NOT NULL) k
            WHERE c.id = k.id AND k.id <> k.keep_id
            """);
        // 4) уникальный индекс — один диалог поддержки на пользователя
        jdbc.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS ux_conversations_support_owner
            ON conversations (support_owner_id) WHERE support_owner_id IS NOT NULL
            """);
    }

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

        Long convId;
        try {
            convId = jdbc.queryForObject(
                "INSERT INTO conversations (support_owner_id, created_at) VALUES (?, NOW()) RETURNING id",
                Long.class, me);
        } catch (DuplicateKeyException e) {
            // гонка через балансер: диалог уже создан другим инстансом — берём его
            Long again = findExistingConversation(me);
            if (again != null) {
                reassignToManagerIfNeeded(again, me);
                return ResponseEntity.ok(Map.of("conversationId", again));
            }
            throw e;
        }

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
        // queryForList вместо queryForObject: если дубли ещё не схлопнулись — берём самый ранний, а не падаем 500
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM conversations WHERE support_owner_id = ? ORDER BY id ASC", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
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
