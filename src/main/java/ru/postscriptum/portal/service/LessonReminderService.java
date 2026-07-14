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
 * Напоминания об уроке за 15 минут: в приложении (колокольчик) и на почту.
 * Раз в минуту берёт уроки, стартующие в ближайшие 15 минут, ещё без напоминания,
 * шлёт участникам (преподаватель + ученики) уведомление и письмо, помечает урок.
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
    void ensureColumn() {
        // флаг «напоминание отправлено» — без ручной миграции
        jdbc.execute("ALTER TABLE lessons ADD COLUMN IF NOT EXISTS reminder_sent BOOLEAN NOT NULL DEFAULT FALSE");
    }

    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 20 * 1000)
    public void sendUpcomingReminders() {
        List<Map<String, Object>> lessons = jdbc.queryForList("""
            SELECT l.id, l.scheduled_at, lang.name_ru AS language
            FROM lessons l
            LEFT JOIN languages lang ON lang.id = l.language_id
            WHERE l.status = 'PLANNED'::lesson_status
              AND l.reminder_sent = FALSE
              AND l.scheduled_at > NOW()
              AND l.scheduled_at <= NOW() + INTERVAL '15 minutes'
            """);

        for (Map<String, Object> l : lessons) {
            long lessonId  = ((Number) l.get("id")).longValue();
            String language = (String) l.get("language");
            Timestamp ts   = (Timestamp) l.get("scheduled_at");
            int minutes = Math.max(1, (int) Math.round((ts.getTime() - System.currentTimeMillis()) / 60000.0));
            String joinUrl = frontendUrl.replaceAll("/+$", "") + "/conference/" + lessonId;

            // участники: преподаватель + ученики урока
            List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT u.id, u.name, u.email FROM users u
                WHERE u.id = (SELECT teacher_id FROM lessons WHERE id = ?)
                UNION
                SELECT u.id, u.name, u.email FROM users u
                JOIN lesson_students ls ON ls.student_id = u.id
                WHERE ls.lesson_id = ?
                """, lessonId, lessonId);

            String title = "Скоро урок";
            String body  = (language != null && !language.isBlank() ? "Урок «" + language + "»" : "Ваш урок")
                    + " начнётся через " + minutes + " мин.";

            for (Map<String, Object> p : people) {
                long uid = ((Number) p.get("id")).longValue();
                try {
                    jdbc.update("""
                        INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
                        VALUES (?, 'LESSON_REMINDER'::notification_type, ?, ?, ?, false, NOW())
                        """, uid, title, body, "/conference/" + lessonId);
                } catch (Exception e) {
                    log.warn("Не удалось создать уведомление об уроке {} для {}: {}", lessonId, uid, e.getMessage());
                }

                String email = (String) p.get("email");
                if (email != null && !email.isBlank()
                        && !email.endsWith("@vk.oauth") && !email.endsWith("@yandex.oauth") && !email.endsWith("@imported.ps")) {
                    mail.sendLessonReminder(email, (String) p.get("name"), language, minutes, joinUrl);
                }
            }

            jdbc.update("UPDATE lessons SET reminder_sent = TRUE WHERE id = ?", lessonId);
            log.info("Напоминание об уроке {} отправлено ({} участникам, через {} мин)", lessonId, people.size(), minutes);
        }
    }
}
