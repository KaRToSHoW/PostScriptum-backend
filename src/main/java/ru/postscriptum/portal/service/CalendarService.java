package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;

/**
 * Месячный календарь занятий. День и время урока считаются в часовом поясе того,
 * кто смотрит (users.timezone), а не в поясе сервера или преподавателя.
 */
@Service
@RequiredArgsConstructor
public class CalendarService {

    private final JdbcTemplate jdbc;
    private final UserZoneService zones;

    private static final Map<String, String> STATUS = Map.of(
        "DONE",        "done",
        "MISSED",      "missed",
        "PLANNED",     "planned",
        "IN_PROGRESS", "now",
        "CANCELLED",   "missed"
    );

    /** Занятия преподавателя за месяц: с учениками, id и точным временем начала (для переноса/отмены). */
    public Map<String, Object> teacherMonth(long teacherId, int year, int month) {
        String tz = zones.current().getId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT
              l.id AS lesson_id,
              EXTRACT(DAY FROM l.scheduled_at AT TIME ZONE ?) AS day,
              TO_CHAR(l.scheduled_at AT TIME ZONE ?, 'HH24:MI') AS time,
              l.scheduled_at,
              lang.code AS lang,
              l.status,
              STRING_AGG(DISTINCT s.name, ', ') AS student_names
            FROM lessons l
            JOIN languages lang ON lang.id = l.language_id
            LEFT JOIN lesson_students ls ON ls.lesson_id = l.id
            LEFT JOIN users s ON s.id = ls.student_id
            WHERE l.teacher_id = ?
              AND EXTRACT(YEAR  FROM l.scheduled_at AT TIME ZONE ?) = ?
              AND EXTRACT(MONTH FROM l.scheduled_at AT TIME ZONE ?) = ?
            GROUP BY l.id, l.scheduled_at, lang.code, l.status
            ORDER BY l.scheduled_at ASC
            """, tz, tz, teacherId, tz, year, tz, month);

        Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> event = event(row, row.get("student_names"));
            event.put("id", row.get("lesson_id"));
            // ISO-8601 в UTC — фронтенд считает «сколько часов до урока» без привязки к поясу браузера
            event.put("scheduledAt", row.get("scheduled_at") instanceof Timestamp ts ? ts.toInstant().toString() : null);
            grouped.computeIfAbsent(day(row), k -> new ArrayList<>()).add(event);
        }
        return result(year, month, grouped);
    }

    /** Занятия ученика за месяц (в т.ч. групповые). Используется и учеником, и его родителем. */
    public Map<String, Object> studentMonth(long studentId, int year, int month) {
        String tz = zones.current().getId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT
              l.id AS lesson_id,
              EXTRACT(DAY FROM l.scheduled_at AT TIME ZONE ?) AS day,
              TO_CHAR(l.scheduled_at AT TIME ZONE ?, 'HH24:MI') AS time,
              lang.code AS lang,
              l.status,
              t.name AS teacher_name
            FROM lesson_students ls
            JOIN lessons l ON l.id = ls.lesson_id
            JOIN languages lang ON lang.id = l.language_id
            JOIN users t ON t.id = l.teacher_id
            WHERE ls.student_id = ?
              AND EXTRACT(YEAR  FROM l.scheduled_at AT TIME ZONE ?) = ?
              AND EXTRACT(MONTH FROM l.scheduled_at AT TIME ZONE ?) = ?
            ORDER BY l.scheduled_at ASC
            """, tz, tz, studentId, tz, year, tz, month);

        Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> event = event(row, row.get("teacher_name"));
            event.put("id", row.get("lesson_id"));   // для материалов урока
            grouped.computeIfAbsent(day(row), k -> new ArrayList<>()).add(event);
        }
        return result(year, month, grouped);
    }

    /** Все занятия школы за месяц (администратор / менеджер), опционально по одному преподавателю. */
    public Map<String, Object> adminMonth(int year, int month, Long teacherId) {
        String tz = zones.current().getId();
        StringBuilder sql = new StringBuilder("""
            SELECT
              l.id AS lesson_id,
              EXTRACT(DAY FROM l.scheduled_at AT TIME ZONE ?) AS day,
              TO_CHAR(l.scheduled_at AT TIME ZONE ?, 'HH24:MI') AS time,
              lang.code AS lang,
              l.status,
              t.name AS teacher_name,
              r.name AS room_name,
              STRING_AGG(DISTINCT s.name, ', ') AS student_names,
              COUNT(DISTINCT ls.student_id) AS student_count
            FROM lessons l
            JOIN languages lang ON lang.id = l.language_id
            JOIN users t ON t.id = l.teacher_id
            LEFT JOIN rooms r ON r.id = l.room_id
            LEFT JOIN lesson_students ls ON ls.lesson_id = l.id
            LEFT JOIN users s ON s.id = ls.student_id
            WHERE EXTRACT(YEAR  FROM l.scheduled_at AT TIME ZONE ?) = ?
              AND EXTRACT(MONTH FROM l.scheduled_at AT TIME ZONE ?) = ?
            """);
        List<Object> args = new ArrayList<>(List.of(tz, tz, tz, year, tz, month));
        if (teacherId != null) {
            sql.append("  AND l.teacher_id = ?\n");
            args.add(teacherId);
        }
        sql.append("""
            GROUP BY l.id, l.scheduled_at, lang.code, l.status, t.name, r.name
            ORDER BY l.scheduled_at ASC
            """);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());

        Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id",       row.get("lesson_id"));
            event.putAll(event(row, row.get("teacher_name")));
            event.put("room",     row.get("room_name"));
            event.put("students", row.get("student_names"));
            grouped.computeIfAbsent(day(row), k -> new ArrayList<>()).add(event);
        }

        Map<String, Object> days = new LinkedHashMap<>();
        grouped.forEach((k, v) -> days.put(String.valueOf(k), v));

        return Map.of(
            "year",   year,
            "month",  month,
            "rooms", List.of(
                Map.of("id", 1, "name", "Онлайн — основной"),
                Map.of("id", 2, "name", "Онлайн — зал 2"),
                Map.of("id", 3, "name", "Офис · каб. 1")
            ),
            "totalLessons", rows.size(),
            "days", days
        );
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private static int day(Map<String, Object> row) {
        return ((Number) row.get("day")).intValue();
    }

    private static Map<String, Object> event(Map<String, Object> row, Object who) {
        String raw = (String) row.get("status");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("t",   row.get("time"));
        event.put("l",   row.get("lang"));
        event.put("s",   STATUS.getOrDefault(raw != null ? raw : "", raw));
        event.put("who", who);
        return event;
    }

    private static Map<String, Object> result(int year, int month, Map<Integer, List<Map<String, Object>>> grouped) {
        Map<String, Object> events = new LinkedHashMap<>();
        grouped.forEach((k, v) -> events.put(String.valueOf(k), v));
        return Map.of("year", year, "month", month, "events", events);
    }
}
