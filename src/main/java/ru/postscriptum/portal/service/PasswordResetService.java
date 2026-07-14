package ru.postscriptum.portal.service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
        boolean hasName = name != null && !name.isBlank();
        String plainGreeting = hasName ? "Здравствуйте, " + name + "!" : "Здравствуйте!";
        String htmlGreeting  = hasName ? "Здравствуйте, " + escape(name) + "!" : "Здравствуйте!";

        // Текстовая версия (для клиентов без HTML)
        String plain = """
            %s

            Вы запросили сброс пароля в Post Scriptum.
            Чтобы задать новый пароль, перейдите по ссылке (действует 1 час):

            %s

            Если вы не запрашивали сброс — просто проигнорируйте это письмо,
            пароль останется прежним.

            Это автоматическое письмо. Наш пингвин не умеет читать ответы,
            поэтому отвечать на него не стоит.

            — Команда Post Scriptum
            """.formatted(plainGreeting, link);

        String html = EMAIL_TEMPLATE
            .replace("{{greeting}}", htmlGreeting)
            .replace("{{link}}", link);

        if (smtpHost == null || smtpHost.isBlank()) {
            // SMTP не настроен — не роняем поток, пишем ссылку в лог (dev-режим)
            log.warn("SMTP не настроен. Ссылка для сброса пароля {}: {}", to, link);
            return;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setFrom(mailFrom, "Post Scriptum");
            helper.setTo(to);
            helper.setSubject("Сброс пароля — Post Scriptum");
            helper.setText(plain, html);   // text + html
            helper.addInline("penguin", new org.springframework.core.io.ClassPathResource("mail/penguin-happy.png"));
            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Не удалось отправить письмо сброса пароля на {}: {}", to, e.getMessage());
        }
    }

    /** Экранируем пользовательское имя, чтобы не сломать HTML. */
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /* Брендовый HTML-шаблон письма (inline-стили — требование почтовых клиентов). */
    private static final String EMAIL_TEMPLATE = """
        <!DOCTYPE html>
        <html lang="ru">
        <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
        <body style="margin:0;padding:0;background:#F4EFE7;font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;">
          <div style="display:none;max-height:0;overflow:hidden;opacity:0;">Ссылка для сброса пароля действует 1 час.</div>
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#F4EFE7;padding:32px 12px;">
            <tr><td align="center">
              <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="width:480px;max-width:100%;background:#ffffff;border-radius:22px;overflow:hidden;box-shadow:0 12px 40px rgba(43,32,115,.12);">

                <!-- Шапка -->
                <tr><td style="background:#6C5CE7;padding:30px 34px;">
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
                    <td style="font-family:'Unbounded',Arial,sans-serif;font-size:22px;font-weight:800;color:#ffffff;letter-spacing:.04em;">
                      POST <span style="color:#FBD38D;">SCRIPTUM</span>
                    </td>
                    <td align="right" width="60"><img src="cid:penguin" alt="" width="56" style="display:block;border:0;"></td>
                  </tr></table>
                  <div style="font-size:13px;color:rgba(255,255,255,.8);font-style:italic;margin-top:6px;">искусство свободной речи</div>
                </td></tr>

                <!-- Тело -->
                <tr><td style="padding:32px 34px 24px;">
                  <div style="font-size:19px;font-weight:800;color:#2B2073;margin-bottom:14px;">{{greeting}}</div>
                  <p style="font-size:15px;line-height:1.6;color:#3d3a52;margin:0 0 20px;">
                    Вы запросили сброс пароля в личном кабинете Post Scriptum.
                    Нажмите на кнопку ниже, чтобы задать новый пароль.
                  </p>

                  <!-- Кнопка -->
                  <table role="presentation" cellpadding="0" cellspacing="0" style="margin:8px 0 22px;"><tr>
                    <td style="border-radius:14px;background:#F29A2E;box-shadow:0 8px 18px rgba(242,154,46,.4);">
                      <a href="{{link}}" target="_blank"
                         style="display:inline-block;padding:15px 34px;font-size:15px;font-weight:800;color:#ffffff;text-decoration:none;border-radius:14px;">
                        Задать новый пароль
                      </a>
                    </td>
                  </tr></table>

                  <p style="font-size:13px;line-height:1.6;color:#8a869c;margin:0 0 6px;">
                    Ссылка действует <b style="color:#6C5CE7;">1 час</b>. Если кнопка не работает, скопируйте адрес в браузер:
                  </p>
                  <p style="font-size:12px;line-height:1.5;word-break:break-all;margin:0 0 22px;">
                    <a href="{{link}}" style="color:#6C5CE7;">{{link}}</a>
                  </p>

                  <div style="height:1px;background:#ece8f5;margin:0 0 18px;"></div>
                  <p style="font-size:13px;line-height:1.6;color:#8a869c;margin:0;">
                    Если вы не запрашивали сброс — просто проигнорируйте это письмо,
                    пароль останется прежним.
                  </p>
                </td></tr>

                <!-- Подвал -->
                <tr><td style="padding:20px 34px 28px;background:#faf8ff;">
                  <p style="font-size:12px;line-height:1.6;color:#a09cb3;margin:0 0 6px;">
                    Это автоматическое письмо. Наш пингвин не умеет читать ответы,
                    поэтому отвечать на него не стоит.
                    <img src="cid:penguin" alt="" width="16" style="vertical-align:-4px;border:0;">
                  </p>
                  <p style="font-size:12px;color:#a09cb3;margin:0;">
                    © Post Scriptum · <a href="https://postscriptum-online.ru" style="color:#6C5CE7;text-decoration:none;">postscriptum-online.ru</a>
                  </p>
                </td></tr>

              </table>
            </td></tr>
          </table>
        </body>
        </html>
        """;

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
