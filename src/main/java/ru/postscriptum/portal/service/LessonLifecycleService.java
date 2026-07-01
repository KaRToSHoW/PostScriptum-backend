package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Не может существовать запланированного урока, время которого уже прошло.
 * Раз в несколько минут закрывает такие уроки:
 *  - DONE, если хотя бы один ученик отмечен как присутствовавший (lesson_students.attended = true)
 *  - MISSED, если посещение никто не подтвердил (ни через подключение к конференции, ни вручную)
 */
@Service
@RequiredArgsConstructor
public class LessonLifecycleService {

    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 30 * 1000)
    public void closeExpiredLessons() {
        // уроки, которые точно состоялись и кто-то подтвердил присутствие
        jdbc.update("""
            UPDATE lessons SET status = 'DONE'::lesson_status
            WHERE status IN ('PLANNED'::lesson_status, 'IN_PROGRESS'::lesson_status)
              AND (scheduled_at + (duration_min || ' minutes')::interval) < NOW()
              AND EXISTS (
                  SELECT 1 FROM lesson_students ls
                  WHERE ls.lesson_id = lessons.id AND ls.attended = true
              )
            """);

        // всё остальное просроченное — никто не подтвердил присутствие
        jdbc.update("""
            UPDATE lessons SET status = 'MISSED'::lesson_status
            WHERE status IN ('PLANNED'::lesson_status, 'IN_PROGRESS'::lesson_status)
              AND (scheduled_at + (duration_min || ' minutes')::interval) < NOW()
            """);
    }
}
