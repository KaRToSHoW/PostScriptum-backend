package ru.postscriptum.portal.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Безопасный сброс пароля по email.
 *
 * Поток:
 *   1) POST /forgot-password { email } → генерируем одноразовый токен, храним ТОЛЬКО его SHA-256,
 *      письмом отправляем ссылку {frontend}/reset-password?token=... со сроком 1 час.
 *   2) POST /reset-password { token, newPassword } → сверяем хэш, срок и «не использован» → меняем пароль.
 *
 * Ответ на forgot-password всегда одинаковый — не раскрываем, зарегистрирован ли email.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PasswordResetService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    private static final long TOKEN_TTL_MINUTES = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.oauth.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Value("${SMTP_HOST:}")
    private String smtpHost;

    @Value("${app.mail.from:no-reply@postscriptum-online.ru}")
    private String mailFrom;

    @PostConstruct
    void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS password_reset_tokens (
                id         BIGSERIAL PRIMARY KEY,
                user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                token_hash VARCHAR(64) NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                used       BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_prt_token_hash ON password_reset_tokens(token_hash)");
    }

    /** Шаг 1. Всегда «тихо» — существование email не раскрываем. */
    public void requestReset(String email) {
        Long userId;
        Boolean active;
        String name;
        try {
            Map<String, Object> u = jdbc.queryForMap(
                "SELECT id, is_active, name FROM users WHERE lower(email) = lower(?)", email);
            userId = ((Number) u.get("id")).longValue();
            active = (Boolean) u.get("is_active");
            name   = (String) u.get("name");
        } catch (Exception e) {
            return; // нет такого пользователя — молча выходим
        }
        if (!Boolean.TRUE.equals(active)) return;

        // гасим прежние неиспользованные токены этого пользователя
        jdbc.update("UPDATE password_reset_tokens SET used = TRUE WHERE user_id = ? AND used = FALSE", userId);

        String token = randomToken();
        jdbc.update("""
            INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
            VALUES (?, ?, NOW() + (? || ' minutes')::interval)
            """, userId, sha256(token), TOKEN_TTL_MINUTES);

        String link = frontendUrl.replaceAll("/+$", "") + "/reset-password?token=" + token;
        sendResetEmail(email, name, link);
    }

    /** Шаг 2. Возвращает true, если пароль успешно изменён. */
    public boolean resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) return false;

        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                SELECT id, user_id FROM password_reset_tokens
                WHERE token_hash = ? AND used = FALSE AND expires_at > NOW()
                ORDER BY id DESC LIMIT 1
                """, sha256(token));
        } catch (Exception e) {
            return false; // токен не найден / просрочен / использован
        }

        long tokenId = ((Number) row.get("id")).longValue();
        long userId  = ((Number) row.get("user_id")).longValue();

        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?",
            passwordEncoder.encode(newPassword), userId);
        jdbc.update("UPDATE password_reset_tokens SET used = TRUE WHERE id = ?", tokenId);
        return true;
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private void sendResetEmail(String to, String name, String link) {
        String body = """
            Здравствуйте, %s!

            Вы запросили сброс пароля в Post Scriptum.
            Чтобы задать новый пароль, перейдите по ссылке (действует 1 час):

            %s

            Если вы не запрашивали сброс — просто проигнорируйте это письмо,
            пароль останется прежним.

            — Команда Post Scriptum
            """.formatted(name != null ? name : "", link);

        if (smtpHost == null || smtpHost.isBlank()) {
            // SMTP не настроен — не роняем поток, пишем ссылку в лог (dev-режим)
            log.warn("SMTP не настроен. Ссылка для сброса пароля {}: {}", to, link);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(mailFrom);
            msg.setTo(to);
            msg.setSubject("Сброс пароля — Post Scriptum");
            msg.setText(body);
            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Не удалось отправить письмо сброса пароля на {}: {}", to, e.getMessage());
        }
    }

    private static String randomToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
