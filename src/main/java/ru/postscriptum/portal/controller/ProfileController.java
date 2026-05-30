package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    @PutMapping
    public ResponseEntity<?> update(Authentication auth, @RequestBody Map<String, Object> body) {
        if (auth == null) return ResponseEntity.status(401).build();

        String email = auth.getName();
        String name      = body.get("name")      != null ? (String) body.get("name")      : null;
        String phone     = body.get("phone")     != null ? (String) body.get("phone")     : null;
        String timezone  = body.get("timezone")  != null ? (String) body.get("timezone")  : null;
        String avatarUrl = body.get("avatarUrl") != null ? (String) body.get("avatarUrl") : null;

        jdbc.update(
            "UPDATE users SET name=COALESCE(?,name), phone=?, timezone=COALESCE(?,timezone), avatar_url=? WHERE email=?",
            name, phone, timezone, avatarUrl, email);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT name, phone, timezone, avatar_url FROM users WHERE email=?", email);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name",      row.get("name"));
        result.put("phone",     row.get("phone"));
        result.put("timezone",  row.get("timezone"));
        result.put("avatarUrl", row.get("avatar_url"));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/password")
    public ResponseEntity<?> changePassword(Authentication auth, @RequestBody Map<String, String> body) {
        if (auth == null) return ResponseEntity.status(401).build();

        String email           = auth.getName();
        String currentPassword = body.get("currentPassword");
        String newPassword     = body.get("newPassword");

        String hash = jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE email=?", String.class, email);

        if (hash == null || !passwordEncoder.matches(currentPassword, hash)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Неверный текущий пароль"));
        }

        jdbc.update("UPDATE users SET password_hash=? WHERE email=?",
            passwordEncoder.encode(newPassword), email);

        return ResponseEntity.ok().build();
    }
}
