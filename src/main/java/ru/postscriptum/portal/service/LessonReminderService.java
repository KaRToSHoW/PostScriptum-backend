package ru.postscriptum.portal.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Персональные напоминания об уроке. У каждого участника своё время «за сколько
 * до урока» (reminder_minutes_before, минуты). Раз в минуту берём пары (урок, участник),
 * для которых порог наступил и напоминание ещё не отправлено, шлём уведомление в
 * колокольчик (его подхватит пуш) и письмо — если у пользователя включена почта.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LessonReminderService {

    private final JdbcTemplate jdbc;
    private final MailService mail;

    @Value("${app.oauth.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("ALTER TABLE lessons ADD COLUMN IF NOT EXISTS reminder_sent BOOLEAN NOT NULL DEFAULT FALSE");
        // индивидуальное время напоминания в минутах (существующим строкам — 15)
        jdbc.execute("ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS reminder_minutes_before INT NOT NULL DEFAULT 15");
        // журнал отправленных напоминаний per (урок, пользователь) — чтобы не слать дважды
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS lesson_reminder_log (
                lesson_id BIGINT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
                user_id   BIGINT NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
                sent_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                PRIMARY KEY (lesson_id, user_id)
            )""");
    }

    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 20 * 1000)
    public void sendUpcomingReminders() {
        List<Map<String, Object>> due = jdbc.queryForList("""
            SELECT l.id AS lesson_id, l.scheduled_at, lang.name_ru AS language,
                   u.id AS user_id, u.name, u.email,
                   COALESCE(us.notification_email, TRUE) AS notif_email
            FROM lessons l
            LEFT JOIN languages lang ON lang.id = l.language_id
            JOIN (
                SELECT id AS lesson_id, teacher_id AS user_id FROM lessons
                UNION
                SELECT lesson_id, student_id FROM lesson_students
            ) part ON part.lesson_id = l.id
            JOIN users u ON u.id = part.user_id
            LEFT JOIN user_settings us ON us.user_id = u.id
            WHERE l.status = 'PLANNED'::lesson_status
              AND l.scheduled_at > NOW()
              AND l.scheduled_at <= NOW() + INTERVAL '48 hours'
              AND l.scheduled_at <= NOW() + COALESCE(us.reminder_minutes_before, 15) * INTERVAL '1 minute'
              AND NOT EXISTS (SELECT 1 FROM lesson_reminder_log r WHERE r.lesson_id = l.id AND r.user_id = u.id)
            """);

        for (Map<String, Object> row : due) {
            long lessonId = ((Number) row.get("lesson_id")).longValue();
            long uid      = ((Number) row.get("user_id")).longValue();
            String language = (String) row.get("language");
            Timestamp ts = (Timestamp) row.get("scheduled_at");
            int minutes = Math.max(1, (int) Math.round((ts.getTime() - System.currentTimeMillis()) / 60000.0));
            boolean notifEmail = Boolean.TRUE.equals(row.get("notif_email"));
            String joinUrl = frontendUrl.replaceAll("/+$", "") + "/conference/" + lessonId;

            // атомарно «застолбить» пару (урок, пользователь); если уже есть — пропускаем
            int claimed;
            try {
                claimed = jdbc.update("INSERT INTO lesson_reminder_log (lesson_id, user_id) VALUES (?,?) ON CONFLICT DO NOTHING", lessonId, uid);
            } catch (Exception e) {
                log.warn("Журнал напоминаний недоступен ({}/{}): {}", lessonId, uid, e.getMessage());
                continue;
            }
            if (claimed == 0) continue;   // уже напоминали (в т.ч. другой инстанс за балансером)

            String title = "Скоро урок";
            String body  = (language != null && !language.isBlank() ? "Урок «" + language + "»" : "Ваш урок")
                    + " начнётся " + humanize(minutes) + ".";
            try {
                jdbc.update("""
                    INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
                    VALUES (?, 'LESSON_REMINDER'::notification_type, ?, ?, ?, false, NOW())
                    """, uid, title, body, "/conference/" + lessonId);
            } catch (Exception e) {
                log.warn("Не удалось создать уведомление об уроке {} для {}: {}", lessonId, uid, e.getMessage());
            }

            String email = (String) row.get("email");
            if (notifEmail && email != null && !email.isBlank()
                    && !email.endsWith("@vk.oauth") && !email.endsWith("@yandex.oauth") && !email.endsWith("@imported.ps")) {
                mail.sendLessonReminder(email, (String) row.get("name"), language, minutes, joinUrl);
            }
        }

        if (!due.isEmpty()) log.info("Напоминания разосланы: {} шт.", due.size());
    }

    /** «через 15 мин» / «через 2 ч 30 мин» / «через 24 ч». */
    private static String humanize(int minutes) {
        if (minutes < 60) return "через " + minutes + " мин";
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? "через " + h + " ч" : "через " + h + " ч " + m + " мин";
    }
}
