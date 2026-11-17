package ru.postscriptum.portal.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Идемпотентное обновление схемы под модель данных из ВКР.
 * Только добавляет столбцы, таблицы и индексы (ничего не удаляет), поэтому безопасно
 * выполняется при каждом старте, в том числе на уже работающей базе.
 *
 * Устаревшие столбцы (users.initials, student_profiles.streak_days, lessons.reminder_sent,
 * lessons.enrollment_id, enrollments.lessons_done, messages.is_read, payments.student_id,
 * homework.teacher_id/student_id/language_id) пока не удаляются: код их больше не использует
 * для логики, удаление — отдельной миграцией после проверки на проде.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaUpdater {

    private final JdbcTemplate jdbc;

    private static final List<String> DDL = List.of(
        // Посещаемость по времени присутствия (веб-хуки LiveKit) и привязка списания к абонементу
        "ALTER TABLE lesson_students ADD COLUMN IF NOT EXISTS subscription_id BIGINT REFERENCES subscriptions(id) ON DELETE SET NULL",
        "ALTER TABLE lesson_students ADD COLUMN IF NOT EXISTS first_joined_at TIMESTAMPTZ",
        "ALTER TABLE lesson_students ADD COLUMN IF NOT EXISTS present_seconds INT NOT NULL DEFAULT 0",
        // служебное: начало текущего (ещё не закрытого) пребывания в комнате
        "ALTER TABLE lesson_students ADD COLUMN IF NOT EXISTS active_since TIMESTAMPTZ",

        // Покупка абонемента: кто купил и возраст ученика (самостоятельно — с 18 лет)
        "ALTER TABLE student_profiles ADD COLUMN IF NOT EXISTS birth_date DATE",
        "ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS purchased_by BIGINT REFERENCES users(id) ON DELETE SET NULL",

        // Прочтение сообщений — у участника диалога, а не у сообщения
        "ALTER TABLE conversation_members ADD COLUMN IF NOT EXISTS last_read_at TIMESTAMPTZ",
        """
        UPDATE conversation_members cm SET last_read_at = COALESCE(
            (SELECT MAX(m.sent_at) FROM messages m
              WHERE m.conversation_id = cm.conversation_id AND m.sender_id <> cm.user_id AND m.is_read),
            to_timestamp(0))
        WHERE cm.last_read_at IS NULL
        """,

        // Домашнее задание на занятие (всем ученикам занятия), решение — у каждого ученика своё
        "ALTER TABLE homework ALTER COLUMN student_id DROP NOT NULL",
        "ALTER TABLE homework_submissions ADD COLUMN IF NOT EXISTS student_id BIGINT REFERENCES users(id) ON DELETE CASCADE",
        "ALTER TABLE homework_submissions ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED'",
        """
        UPDATE homework_submissions hs SET student_id = h.student_id
        FROM homework h WHERE hs.homework_id = h.id AND hs.student_id IS NULL
        """,
        "UPDATE homework_submissions SET status = 'REVIEWED' WHERE reviewed_at IS NOT NULL AND status <> 'REVIEWED'",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_homework_submissions_hw_student ON homework_submissions(homework_id, student_id)",

        // Окна доступности преподавателя
        """
        CREATE TABLE IF NOT EXISTS teacher_availability (
            id          BIGSERIAL PRIMARY KEY,
            teacher_id  BIGINT   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            weekday     SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
            start_time  TIME     NOT NULL,
            end_time    TIME     NOT NULL CHECK (end_time > start_time)
        )
        """,
        "ALTER TABLE teacher_availability ADD COLUMN IF NOT EXISTS weekday SMALLINT",
        "ALTER TABLE teacher_availability ADD COLUMN IF NOT EXISTS start_time TIME",
        "ALTER TABLE teacher_availability ADD COLUMN IF NOT EXISTS end_time TIME",
        // старая версия таблицы (day_of_week, time_from, time_to) не использовалась, но её NOT NULL
        // не давали сохранить окна в новых столбцах
        "ALTER TABLE teacher_availability ALTER COLUMN day_of_week DROP NOT NULL",
        "ALTER TABLE teacher_availability ALTER COLUMN time_from DROP NOT NULL",
        "ALTER TABLE teacher_availability ALTER COLUMN time_to DROP NOT NULL",
        "CREATE INDEX IF NOT EXISTS ix_teacher_availability_teacher ON teacher_availability(teacher_id)",

        // Материалы к занятию: файлы (загружаются через /api/files) и ссылки
        """
        CREATE TABLE IF NOT EXISTS lesson_materials (
            id          BIGSERIAL    PRIMARY KEY,
            lesson_id   BIGINT       NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
            uploaded_by BIGINT       REFERENCES users(id) ON DELETE SET NULL,
            kind        VARCHAR(10)  NOT NULL DEFAULT 'FILE' CHECK (kind IN ('FILE', 'LINK')),
            title       VARCHAR(255) NOT NULL,
            url         TEXT         NOT NULL,
            file_size   BIGINT,
            created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
        )
        """,
        "CREATE INDEX IF NOT EXISTS ix_lesson_materials_lesson ON lesson_materials(lesson_id)"
    );

    @PostConstruct
    public void update() {
        for (String sql : DDL) {
            try {
                jdbc.execute(sql);
            } catch (Exception e) {
                // одна неудачная команда (например, другая СУБД в dev) не должна ронять приложение
                log.warn("Schema update skipped: {} — {}", firstLine(sql), e.getMessage());
            }
        }
    }

    private static String firstLine(String sql) {
        String s = sql.strip();
        int nl = s.indexOf('\n');
        return nl > 0 ? s.substring(0, nl) : s;
    }
}
