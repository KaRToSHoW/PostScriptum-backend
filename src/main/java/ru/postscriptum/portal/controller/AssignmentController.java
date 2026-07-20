package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AssignmentController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final ru.postscriptum.portal.service.MailService mailService;

    // ─── POST /api/admin/assign-teacher ────────────────────────────────────

    @PostMapping("/assign-teacher")
    public ResponseEntity<?> assignTeacher(@RequestBody Map<String, Object> body,
                                           Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        try {
            long studentId = toLong(body.get("studentId"));
            long teacherId = toLong(body.get("teacherId"));
            String languageCode = (String) body.get("languageCode");

            ResponseEntity<?> err = doAssign(studentId, teacherId, languageCode);
            if (err != null) return err;

            return ResponseEntity.ok(Map.of("ok", true));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Общая логика назначения: enrollment + уведомления. null — успех, иначе ответ-ошибка. */
    private ResponseEntity<?> doAssign(long studentId, long teacherId, String languageCode) {
        // 1. Find language id
        Long languageId;
        try {
            languageId = jdbc.queryForObject(
                "SELECT id FROM languages WHERE code=?", Long.class, languageCode
            );
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Язык не найден: " + languageCode));
        }

        // 2. Check existing enrollment for this student+teacher+language
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id FROM enrollments WHERE student_id=? AND teacher_id=? AND language_id=?",
            studentId, teacherId, languageId
        );
        if (!existing.isEmpty()) {
            jdbc.update(
                "UPDATE enrollments SET is_active=true WHERE student_id=? AND teacher_id=? AND language_id=?",
                studentId, teacherId, languageId
            );
        } else {
            jdbc.update(
                "INSERT INTO enrollments (student_id, teacher_id, language_id, start_date, " +
                "                        lessons_done, lessons_total, is_active) " +
                "VALUES (?, ?, ?, NOW()::date, 0, 0, true)",
                studentId, teacherId, languageId
            );
        }

        // 3. Notify student
        jdbc.update(
            "INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at) " +
            "VALUES (?, 'SYSTEM'::notification_type, 'Назначение преподавателя', " +
            "        'Вам назначен новый преподаватель', '/teachers', false, NOW())",
            studentId
        );
        // Notify teacher
        jdbc.update(
            "INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at) " +
            "VALUES (?, 'SYSTEM'::notification_type, 'Новый ученик', " +
            "        'Вам назначен новый ученик', '/students', false, NOW())",
            teacherId
        );

        return null;
    }

    // ─── POST /api/admin/leads/{id}/assign ────────────────────────────────
    // Заявка от уже зарегистрировавшегося ученика: аккаунт существует,
    // создавать ничего не нужно — только распределить к преподавателю.

    @PostMapping("/leads/{id}/assign")
    public ResponseEntity<?> assignLead(@PathVariable long id,
                                        @RequestBody Map<String, Object> body,
                                        Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        try {
            Map<String, Object> lead;
            try {
                lead = jdbc.queryForMap("SELECT email FROM leads WHERE id=?", id);
            } catch (EmptyResultDataAccessException e) {
                return ResponseEntity.badRequest().body(Map.of("message", "Лид не найден"));
            }

            String email = (String) lead.get("email");
            if (email == null || email.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "У заявки нет email — аккаунт не найти"));
            }

            Long studentId;
            try {
                studentId = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
            } catch (EmptyResultDataAccessException e) {
                return ResponseEntity.badRequest().body(Map.of("message", "Аккаунт с email " + email + " не найден"));
            }

            long teacherId = toLong(body.get("teacherId"));
            String languageCode = (String) body.get("languageCode");

            ResponseEntity<?> err = doAssign(studentId, teacherId, languageCode);
            if (err != null) return err;

            // профиль ученика на всякий случай + закрываем заявку
            jdbc.update(
                "INSERT INTO student_profiles (user_id, streak_days) VALUES (?, 0) ON CONFLICT DO NOTHING",
                studentId
            );
            jdbc.update(
                "UPDATE leads SET status='CONVERTED'::lead_status, converted_at=NOW(), converted_user_id=? WHERE id=?",
                studentId, id
            );

            return ResponseEntity.ok(Map.of("ok", true, "studentId", studentId));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ─── POST /api/admin/link-parent ───────────────────────────────────────

    @PostMapping("/link-parent")
    public ResponseEntity<?> linkParent(@RequestBody Map<String, Object> body,
                                        Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        long parentId  = toLong(body.get("parentId"));
        long studentId = toLong(body.get("studentId"));

        // Ensure student_profiles row exists
        jdbc.update(
            "INSERT INTO student_profiles (user_id, streak_days) VALUES (?, 0) ON CONFLICT DO NOTHING",
            studentId
        );

        // Set parent_id
        jdbc.update(
            "UPDATE student_profiles SET parent_id=? WHERE user_id=?",
            parentId, studentId
        );

        return ResponseEntity.ok().build();
    }

    // ─── POST /api/admin/leads/{id}/convert ───────────────────────────────

    @PostMapping("/leads/{id}/convert")
    public ResponseEntity<?> convertLead(@PathVariable long id,
                                         Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        try {
            // Load lead
            Map<String, Object> lead;
            try {
                lead = jdbc.queryForMap("SELECT name, email, phone FROM leads WHERE id=?", id);
            } catch (EmptyResultDataAccessException e) {
                return ResponseEntity.badRequest().body(Map.of("message", "Лид не найден"));
            }

            String name  = (String) lead.get("name");
            String email = (String) lead.get("email");
            boolean hasRealEmail = email != null && !email.isBlank();   // есть куда слать доступы
            if (!hasRealEmail) {
                email = "lead" + id + "@imported.ps";
            }

            String initials = computeInitials(name);
            String plainPassword = generatePassword();               // отдадим менеджеру, чтобы передать клиенту
            String tempPassword = passwordEncoder.encode(plainPassword);

            final String finalEmail = email;
            final String finalName  = name;
            // RETURNING id — надёжнее getGeneratedKeys (Postgres иначе возвращает все колонки)
            long userId = jdbc.queryForObject(
                "INSERT INTO users (name, email, initials, password_hash, role, " +
                "                  is_active, timezone, locale, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'STUDENT'::user_role, true, 'Europe/Moscow', 'ru', NOW(), NOW()) RETURNING id",
                Long.class, finalName, finalEmail, initials, tempPassword);

            // Insert student_profiles
            jdbc.update(
                "INSERT INTO student_profiles (user_id, streak_days) VALUES (?, 0) ON CONFLICT DO NOTHING",
                userId
            );

            // Update lead
            jdbc.update(
                "UPDATE leads SET status='CONVERTED'::lead_status, converted_at=NOW(), converted_user_id=? WHERE id=?",
                userId, id
            );

            // если у заявки был настоящий email — сразу шлём доступы письмом
            boolean emailed = false;
            if (hasRealEmail) {
                emailed = mailService.sendAccountCredentials(finalEmail, finalName, finalEmail, plainPassword);
            }

            // возвращаем логин и временный пароль — менеджер передаст их клиенту (если письмо не ушло)
            return ResponseEntity.ok(Map.of(
                "userId",   userId,
                "email",    finalEmail,
                "password", plainPassword,
                "emailed",  emailed));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(val));
    }

    private String computeInitials(String name) {
        if (name == null || name.isBlank()) return "??";
        String[] words = name.trim().split("\\s+");
        return Arrays.stream(words)
                .filter(w -> !w.isEmpty())
                .limit(2)
                .map(w -> String.valueOf(w.charAt(0)).toUpperCase())
                .collect(Collectors.joining());
    }

    // Читаемый временный пароль без похожих символов (0/O, 1/l/I) — удобно продиктовать по телефону
    private static final java.security.SecureRandom RND = new java.security.SecureRandom();
    private static final String PWD_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(PWD_ALPHABET.charAt(RND.nextInt(PWD_ALPHABET.length())));
        return sb.toString();
    }
}
