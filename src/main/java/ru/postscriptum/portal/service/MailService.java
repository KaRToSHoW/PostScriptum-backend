package ru.postscriptum.portal.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Транзакционные письма (кроме сброса пароля, который живёт в PasswordResetService).
 * Если SMTP не настроен (SMTP_HOST пуст) — письмо не шлём, пишем в лог и возвращаем false.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${SMTP_HOST:}")                                 private String smtpHost;
    @Value("${app.mail.from:no-reply@postscriptum-online.ru}") private String mailFrom;
    @Value("${app.oauth.frontend-url:http://localhost:5173}")  private String frontendUrl;

    /**
     * Письмо новому ученику с логином и временным паролем.
     * @return true, если письмо отправлено (SMTP настроен и отправка прошла).
     */
    public boolean sendAccountCredentials(String to, String name, String login, String password) {
        String loginUrl = frontendUrl.replaceAll("/+$", "") + "/login";

        if (smtpHost == null || smtpHost.isBlank()) {
            log.warn("SMTP не настроен. Доступы для {}: login={} pass={}", to, login, password);
            return false;
        }

        boolean hasName = name != null && !name.isBlank();
        String greetingPlain = hasName ? "Здравствуйте, " + name + "!" : "Здравствуйте!";
        String greetingHtml  = hasName ? "Здравствуйте, " + escape(name) + "!" : "Здравствуйте!";

        String plain = """
            %s

            Для вас создан личный кабинет в онлайн-школе Post Scriptum.
            Данные для входа:

            Логин: %s
            Пароль: %s

            Войти: %s

            Рекомендуем сменить пароль в настройках после первого входа.

            Это автоматическое письмо. Наш пингвин не умеет читать ответы,
            поэтому отвечать на него не стоит.

            — Команда Post Scriptum
            """.formatted(greetingPlain, login, password, loginUrl);

        String html = EMAIL_TEMPLATE
            .replace("{{greeting}}", greetingHtml)
            .replace("{{login}}", escape(login))
            .replace("{{password}}", escape(password))
            .replace("{{loginUrl}}", loginUrl);

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setFrom(mailFrom, "Post Scriptum");
            helper.setTo(to);
            helper.setSubject("Ваш доступ в Post Scriptum");
            helper.setText(plain, html);
            mailSender.send(msg);
            return true;
        } catch (Exception e) {
            log.error("Не удалось отправить письмо с доступами на {}: {}", to, e.getMessage());
            return false;
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String EMAIL_TEMPLATE = """
        <!DOCTYPE html>
        <html lang="ru">
        <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
        <body style="margin:0;padding:0;background:#F4EFE7;font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;">
          <div style="display:none;max-height:0;overflow:hidden;opacity:0;">Данные для входа в Post Scriptum.</div>
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#F4EFE7;padding:32px 12px;">
            <tr><td align="center">
              <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="width:480px;max-width:100%;background:#ffffff;border-radius:22px;overflow:hidden;box-shadow:0 12px 40px rgba(43,32,115,.12);">

                <tr><td style="background:#6C5CE7;padding:30px 34px;">
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
                    <td style="font-family:'Unbounded',Arial,sans-serif;font-size:22px;font-weight:800;color:#ffffff;letter-spacing:.04em;">
                      POST <span style="color:#FBD38D;">SCRIPTUM</span>
                    </td>
                    <td align="right" style="font-size:30px;">🐧</td>
                  </tr></table>
                  <div style="font-size:13px;color:rgba(255,255,255,.8);font-style:italic;margin-top:6px;">искусство свободной речи</div>
                </td></tr>

                <tr><td style="padding:32px 34px 24px;">
                  <div style="font-size:19px;font-weight:800;color:#2B2073;margin-bottom:14px;">{{greeting}}</div>
                  <p style="font-size:15px;line-height:1.6;color:#3d3a52;margin:0 0 20px;">
                    Мы завели для вас личный кабинет в онлайн-школе Post Scriptum. Вот данные для входа:
                  </p>

                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#faf8ff;border:1px solid #ece8f5;border-radius:14px;margin:0 0 22px;">
                    <tr><td style="padding:16px 20px;">
                      <div style="font-size:11px;font-weight:800;color:#a09cb3;letter-spacing:.1em;text-transform:uppercase;">Логин</div>
                      <div style="font-size:16px;font-weight:700;color:#2B2073;margin:2px 0 12px;">{{login}}</div>
                      <div style="font-size:11px;font-weight:800;color:#a09cb3;letter-spacing:.1em;text-transform:uppercase;">Пароль</div>
                      <div style="font-size:18px;font-weight:800;color:#6C5CE7;letter-spacing:.04em;font-family:Consolas,Menlo,monospace;margin-top:2px;">{{password}}</div>
                    </td></tr>
                  </table>

                  <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 22px;"><tr>
                    <td style="border-radius:14px;background:#F29A2E;box-shadow:0 8px 18px rgba(242,154,46,.4);">
                      <a href="{{loginUrl}}" target="_blank"
                         style="display:inline-block;padding:15px 34px;font-size:15px;font-weight:800;color:#ffffff;text-decoration:none;border-radius:14px;">
                        Войти в кабинет
                      </a>
                    </td>
                  </tr></table>

                  <p style="font-size:13px;line-height:1.6;color:#8a869c;margin:0;">
                    После первого входа рекомендуем сменить пароль в настройках профиля.
                  </p>
                </td></tr>

                <tr><td style="padding:20px 34px 28px;background:#faf8ff;">
                  <p style="font-size:12px;line-height:1.6;color:#a09cb3;margin:0 0 6px;">
                    Это автоматическое письмо. Наш пингвин не умеет читать ответы,
                    поэтому отвечать на него не стоит. 🐧
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
}
