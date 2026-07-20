package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Публичная заявка «запись через менеджера» со страницы входа.
 * Создаёт лид (status = NEW) и уведомляет менеджеров/админов.
 */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final JdbcTemplate jdbc;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String name    = trimOrNull(body.get("name"));
        String phone   = trimOrNull(body.get("phone"));
        String email   = trimOrNull(body.get("email"));
        String comment = trimOrNull(body.get("comment"));

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Укажите имя"));
        }
        if (phone == null && email == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Укажите телефон или email для связи"));
        }

        jdbc.update(
            "INSERT INTO leads (name, phone, email, notes, source, status, received_at) " +
            "VALUES (?, ?, ?, ?, ?, 'NEW'::lead_status, NOW())",
            name, phone, email, comment, "Форма на сайте");

        // уведомляем всех активных менеджеров и админов о новой заявке
        try {
            String contact = phone != null ? phone : email;
            jdbc.update("""
                INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
                SELECT u.id, 'NEW_MESSAGE'::notification_type, 'Новая заявка на запись',
                       ?, '/leads', false, NOW()
                FROM users u
                WHERE u.role IN ('MANAGER'::user_role, 'ADMIN'::user_role) AND u.is_active = true
                """, name + " · " + contact);
        } catch (Exception ignored) { /* уведомления не критичны — заявка уже создана */ }

        return ResponseEntity.ok(Map.of("message", "Заявка принята"));
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
