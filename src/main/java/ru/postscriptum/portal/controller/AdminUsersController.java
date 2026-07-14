package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUsersController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    // ─── GET / ─────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> listUsers(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> result = jdbc.query(
            "SELECT u.id, u.name, u.email, u.initials, u.role, u.phone, u.is_active, u.avatar_url, " +
            "       sp.parent_id, p.name AS parent_name, " +
            "       (SELECT COUNT(*) FROM enrollments e WHERE e.student_id = u.id) AS enrollments " +
            "FROM users u " +
            "LEFT JOIN student_profiles sp ON sp.user_id = u.id " +
            "LEFT JOIN users p ON p.id = sp.parent_id " +
            "ORDER BY u.role, u.name",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",          rs.getLong("id"));
                row.put("name",        rs.getString("name"));
                row.put("email",       rs.getString("email"));
                row.put("initials",    rs.getString("initials"));
                row.put("role",        rs.getString("role"));
                row.put("phone",       rs.getString("phone"));
                row.put("avatarUrl",   rs.getString("avatar_url"));
                row.put("active",      rs.getBoolean("is_active"));
                long parentId = rs.getLong("parent_id");
                row.put("parentId",    rs.wasNull() ? null : parentId);
                row.put("parentName",  rs.getString("parent_name"));
                row.put("enrollments", rs.getLong("enrollments"));
                return row;
            }
        );

        return ResponseEntity.ok(result);
    }

    // ─── POST / ────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> body,
                                        Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        String name     = (String) body.get("name");
        String email    = (String) body.get("email");
        String password = (String) body.get("password");
        String role     = (String) body.get("role");

        String initials = computeInitials(name);
        String hash     = passwordEncoder.encode(password);

        try {
            long userId = jdbc.queryForObject("""
                INSERT INTO users (name, email, initials, password_hash, role,
                                   is_active, timezone, locale, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::user_role, true, 'Europe/Moscow', 'ru', NOW(), NOW())
                RETURNING id
                """, Long.class, name, email, initials, hash, role);

            if ("STUDENT".equals(role)) {
                jdbc.update(
                    "INSERT INTO student_profiles (user_id, streak_days) VALUES (?, 0) ON CONFLICT DO NOTHING",
                    userId
                );
            } else if ("TEACHER".equals(role)) {
                jdbc.update(
                    "INSERT INTO teacher_profiles (user_id, capacity_hours, is_native) VALUES (?, 20, false) ON CONFLICT DO NOTHING",
                    userId
                );
            }

            return ResponseEntity.ok(Map.of("id", userId));

        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(409).body(Map.of("message", "Email уже используется"));
        }
    }

    // ─── PUT /{id}/role ────────────────────────────────────────────────────

    @PutMapping("/{id}/role")
    public ResponseEntity<?> updateRole(@PathVariable long id,
                                        @RequestBody Map<String, String> body,
                                        Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        String role = body.get("role");
        if (role == null || role.isBlank()) return ResponseEntity.badRequest().build();

        jdbc.update(
            "UPDATE users SET role=?::user_role, updated_at=NOW() WHERE id=?",
            role, id
        );

        if ("STUDENT".equals(role)) {
            jdbc.update(
                "INSERT INTO student_profiles (user_id, streak_days) VALUES (?, 0) ON CONFLICT DO NOTHING",
                id
            );
        } else if ("TEACHER".equals(role)) {
            jdbc.update(
                "INSERT INTO teacher_profiles (user_id, capacity_hours, is_native) VALUES (?, 20, false) ON CONFLICT DO NOTHING",
                id
            );
        }

        return ResponseEntity.ok().build();
    }

    // ─── PUT /{id}/active ─────────────────────────────────────────────────

    @PutMapping("/{id}/active")
    public ResponseEntity<?> updateActive(@PathVariable long id,
                                          @RequestBody Map<String, Object> body,
                                          Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        boolean active = Boolean.TRUE.equals(body.get("active"));
        jdbc.update(
            "UPDATE users SET is_active=?, updated_at=NOW() WHERE id=?",
            active, id
        );

        return ResponseEntity.ok().build();
    }

    // ─── DELETE /{id} ──────────────────────────────────────────────────────

    /**
     * Удаление пользователя. Аккаунты с учебными/финансовыми данными удалять нельзя
     * (их нужно деактивировать) — так мы не теряем историю уроков и оплат.
     * Для «пустых» аккаунтов (тестовые, ошибочные, свежие заявки) чистим переписку и удаляем.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteUser(@PathVariable long id, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        Long myId = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, auth.getName());
        if (myId != null && myId == id) {
            return ResponseEntity.status(409).body(Map.of("message", "Нельзя удалить свой аккаунт"));
        }

        // блокируем удаление, если есть учебные/финансовые/авторские связи — такие аккаунты деактивируют
        Integer data = jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) FROM enrollments                 WHERE student_id=? OR teacher_id=?)
                 + (SELECT COUNT(*) FROM lessons                     WHERE teacher_id=?)
                 + (SELECT COUNT(*) FROM lesson_students             WHERE student_id=?)
                 + (SELECT COUNT(*) FROM subscriptions               WHERE student_id=?)
                 + (SELECT COUNT(*) FROM payments                    WHERE student_id=?)
                 + (SELECT COUNT(*) FROM homework                    WHERE student_id=? OR teacher_id=?)
                 + (SELECT COUNT(*) FROM speaking_clubs              WHERE teacher_id=?)
                 + (SELECT COUNT(*) FROM speaking_club_registrations WHERE student_id=?)
                 + (SELECT COUNT(*) FROM reports                     WHERE created_by=?)
                 + (SELECT COUNT(*) FROM course_materials            WHERE created_by=?)
                 + (SELECT COUNT(*) FROM payment_refunds             WHERE processed_by=?)
                 + (SELECT COUNT(*) FROM student_profiles            WHERE parent_id=?)
            """, Integer.class, id, id, id, id, id, id, id, id, id, id, id, id, id, id);
        if (data != null && data > 0) {
            return ResponseEntity.status(409).body(Map.of("message",
                "У пользователя есть учебные или финансовые данные. Деактивируйте аккаунт вместо удаления."));
        }

        // чистим побочные ссылки (переписка, заявки, журналы) — иначе внешние ключи не дадут удалить
        jdbc.update("DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE support_owner_id=?)", id);
        jdbc.update("DELETE FROM conversations       WHERE support_owner_id=?", id);
        jdbc.update("DELETE FROM messages            WHERE sender_id=?", id);
        jdbc.update("DELETE FROM lead_activities     WHERE user_id=?", id);
        jdbc.update("DELETE FROM audit_log           WHERE user_id=?", id);
        jdbc.update("DELETE FROM student_attention   WHERE teacher_id=? OR student_id=?", id, id);
        jdbc.update("DELETE FROM teacher_availability WHERE teacher_id=?", id);
        jdbc.update("UPDATE leads                SET assigned_to=NULL      WHERE assigned_to=?", id);
        jdbc.update("UPDATE leads                SET converted_user_id=NULL WHERE converted_user_id=?", id);
        jdbc.update("UPDATE system_settings      SET updated_by=NULL       WHERE updated_by=?", id);
        jdbc.update("UPDATE homework_submissions SET reviewed_by=NULL      WHERE reviewed_by=?", id);
        jdbc.update("UPDATE lesson_events        SET changed_by=NULL       WHERE changed_by=?", id);
        jdbc.update("UPDATE lesson_history       SET changed_by=NULL       WHERE changed_by=?", id);
        // остальное (notifications, conversation_members, user_settings, профили, токены, stored_files) — каскад/SET NULL

        int rows = jdbc.update("DELETE FROM users WHERE id=?", id);
        if (rows == 0) return ResponseEntity.status(404).body(Map.of("message", "Пользователь не найден"));
        return ResponseEntity.ok().build();
    }

    /** Любая недочищенная связь → аккуратный 409 «деактивируйте», а не 500 (@Transactional откатит очистку). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> onRelatedData(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(Map.of("message",
            "У пользователя есть связанные данные. Деактивируйте аккаунт вместо удаления."));
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private String computeInitials(String name) {
        if (name == null || name.isBlank()) return "??";
        String[] words = name.trim().split("\\s+");
        return Arrays.stream(words)
                .filter(w -> !w.isEmpty())
                .limit(2)
                .map(w -> String.valueOf(w.charAt(0)).toUpperCase())
                .collect(Collectors.joining());
    }
}
