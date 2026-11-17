package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Учёт присутствия на занятии по событиям медиасервера.
 * Время накапливается по сеансам (вход → выход), поэтому переподключения не теряют время.
 * Отметка посещения ставится по порогу в процентах от длительности занятия;
 * ручная отметка преподавателя (attended уже не NULL) имеет приоритет.
 */
@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final JdbcTemplate jdbc;

    @Value("${app.attendance.threshold-percent:50}")
    private int thresholdPercent;

    /** Участник вошёл в комнату. Для ученика открывается сеанс присутствия. */
    public void onJoin(long lessonId, long userId, Instant at) {
        Timestamp ts = Timestamp.from(at);
        jdbc.update("""
            UPDATE lesson_students
            SET first_joined_at = COALESCE(first_joined_at, ?), active_since = COALESCE(active_since, ?)
            WHERE lesson_id = ? AND student_id = ?
            """, ts, ts, lessonId, userId);
        jdbc.update(
            "UPDATE lessons SET status='IN_PROGRESS'::lesson_status WHERE id=? AND status='PLANNED'::lesson_status",
            lessonId);
    }

    /** Участник вышел (или оборвалась связь): время сеанса добавляется к присутствию. */
    public void onLeave(long lessonId, long userId, Instant at) {
        jdbc.update("""
            UPDATE lesson_students
            SET present_seconds = present_seconds + GREATEST(0, EXTRACT(EPOCH FROM (?::timestamptz - active_since)))::int,
                active_since = NULL
            WHERE lesson_id = ? AND student_id = ? AND active_since IS NOT NULL
            """, Timestamp.from(at), lessonId, userId);
    }

    /** Закрывает все открытые сеансы занятия (комната закрыта, занятие завершено). */
    public void closeOpenSessions(long lessonId, Instant at) {
        jdbc.update("""
            UPDATE lesson_students
            SET present_seconds = present_seconds + GREATEST(0, EXTRACT(EPOCH FROM (?::timestamptz - active_since)))::int,
                active_since = NULL
            WHERE lesson_id = ? AND active_since IS NOT NULL
            """, Timestamp.from(at), lessonId);
    }

    /** Ставит отметки посещения по порогу тем ученикам, кого преподаватель не отметил вручную. */
    public void applyThreshold(long lessonId) {
        jdbc.update("""
            UPDATE lesson_students ls
            SET attended = (ls.present_seconds >= l.duration_min * 60 * ? / 100)
            FROM lessons l
            WHERE l.id = ls.lesson_id AND ls.lesson_id = ? AND ls.attended IS NULL
            """, thresholdPercent, lessonId);
    }

    public int thresholdPercent() {
        return thresholdPercent;
    }
}
