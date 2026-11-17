package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Не может существовать запланированного урока, время которого уже прошло.
 * Раз в несколько минут закрывает такие уроки, если преподаватель не завершил их сам:
 *  - закрывает незавершённые сеансы присутствия и ставит отметки посещения по порогу;
 *  - DONE, если хотя бы один ученик присутствовал, и списывает занятие с абонементов;
 *  - MISSED, если не присутствовал никто.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LessonLifecycleService {

    private final JdbcTemplate jdbc;
    private final AttendanceService attendance;
    private final BillingService billing;
    private final LiveKitService liveKit;

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 30 * 1000)
    public void closeExpiredLessons() {
        List<Long> expired = jdbc.queryForList("""
            SELECT id FROM lessons
            WHERE status IN ('PLANNED'::lesson_status, 'IN_PROGRESS'::lesson_status)
              AND (scheduled_at + (duration_min || ' minutes')::interval) < NOW()
            """, Long.class);

        for (Long lessonId : expired) {
            try {
                closeLesson(lessonId);
            } catch (Exception e) {
                log.warn("Не удалось закрыть прошедшее занятие {}: {}", lessonId, e.getMessage());
            }
        }
    }

    private void closeLesson(long lessonId) {
        attendance.closeOpenSessions(lessonId, Instant.now());
        attendance.applyThreshold(lessonId);

        Integer anyAttended = jdbc.queryForObject(
            "SELECT COUNT(*) FROM lesson_students WHERE lesson_id=? AND attended = true", Integer.class, lessonId);
        String newStatus = anyAttended != null && anyAttended > 0 ? "DONE" : "MISSED";

        int updated = jdbc.update(
            "UPDATE lessons SET status = ?::lesson_status WHERE id = ? AND status IN ('PLANNED'::lesson_status, 'IN_PROGRESS'::lesson_status)",
            newStatus, lessonId);
        if (updated > 0) {
            billing.settleLesson(lessonId);
            liveKit.closeRoom(lessonId);
        }
    }
}
