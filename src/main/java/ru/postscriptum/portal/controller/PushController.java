package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.service.PushSenderService;

import java.util.Map;

/** Подписки браузера на Web Push. */
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushController {

    private final JdbcTemplate jdbc;
    private final PushSenderService push;

    /** Публичный VAPID-ключ — нужен фронту для оформления подписки. */
    @GetMapping("/public-key")
    public ResponseEntity<?> publicKey() {
        String key = push.getPublicKey();
        if (key == null) return ResponseEntity.status(503).body(Map.of("message", "Push не настроен"));
        return ResponseEntity.ok(Map.of("key", key));
    }

    @PostMapping("/subscribe")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> subscribe(@RequestBody Map<String, Object> body, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Long me = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, auth.getName());

        String endpoint = (String) body.get("endpoint");
        Map<String, Object> keys = (Map<String, Object>) body.get("keys");
        if (endpoint == null || keys == null) return ResponseEntity.badRequest().build();
        String p256dh = (String) keys.get("p256dh");
        String authKey = (String) keys.get("auth");
        if (p256dh == null || authKey == null) return ResponseEntity.badRequest().build();

        jdbc.update("""
            INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth)
            VALUES (?,?,?,?)
            ON CONFLICT (endpoint) DO UPDATE
              SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth
            """, me, endpoint, p256dh, authKey);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<?> unsubscribe(@RequestBody Map<String, Object> body, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        String endpoint = (String) body.get("endpoint");
        if (endpoint != null) jdbc.update("DELETE FROM push_subscriptions WHERE endpoint=?", endpoint);
        return ResponseEntity.ok().build();
    }
}
