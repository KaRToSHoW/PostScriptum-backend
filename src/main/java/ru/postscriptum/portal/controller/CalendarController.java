package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.repository.UserRepository;

import java.util.*;

@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> student(
            Authentication auth,
            @RequestParam(defaultValue = "2026") int year,
            @RequestParam(defaultValue = "5")    int month) {

        if (auth == null) {
            return ResponseEntity.ok(Map.of("year", year, "month", month, "events", Map.of()));
        }

        String email = auth.getName();

        String sql = """
            SELECT
              EXTRACT(DAY FROM l.scheduled_at AT TIME ZONE 'Europe/Moscow') AS day,
              TO_CHAR(l.scheduled_at AT TIME ZONE 'Europe/Moscow', 'HH24:MI') AS time,
              lang.code AS lang,
              l.status,
              t.name AS teacher_name
            FROM lesson_students ls
            JOIN lessons l ON l.id = ls.lesson_id
            JOIN languages lang ON lang.id = l.language_id
            JOIN users u ON u.id = ls.student_id
            JOIN users t ON t.id = l.teacher_id
            WHERE u.email = ?
              AND EXTRACT(YEAR FROM l.scheduled_at AT TIME ZONE 'Europe/Moscow') = ?
              AND EXTRACT(MONTH FROM l.scheduled_at AT TIME ZONE 'Europe/Moscow') = ?
            ORDER BY l.scheduled_at ASC
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, email, year, month);

        // Status mapping
        Map<String, String> statusMap = Map.of(
            "DONE",        "done",
            "MISSED",      "missed",
            "PLANNED",     "planned",
            "IN_PROGRESS", "now",
            "CANCELLED",   "missed"
        );

        // Group by day
        Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int day = ((Number) row.get("day")).intValue();
            String rawStatus = (String) row.get("status");
            String mappedStatus = statusMap.getOrDefault(rawStatus != null ? rawStatus : "", rawStatus);

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("t", row.get("time"));
            event.put("l", row.get("lang"));
            event.put("s", mappedStatus);
            event.put("who", row.get("teacher_name"));

            grouped.computeIfAbsent(day, k -> new ArrayList<>()).add(event);
        }

        // Convert Integer keys to String keys for JSON
        Map<String, Object> events = new LinkedHashMap<>();
        grouped.forEach((k, v) -> events.put(String.valueOf(k), v));

        return ResponseEntity.ok(Map.of(
            "year",   year,
            "month",  month,
            "events", events
        ));
    }

    @GetMapping("/admin")
    public ResponseEntity<?> admin(
            @RequestParam(defaultValue = "2026") int year,
            @RequestParam(defaultValue = "5")    int month) {

        String sql = """
            SELECT
              EXTRACT(DAY FROM l.scheduled_at AT TIME ZONE 'Europe/Moscow') AS day,
              TO_CHAR(l.scheduled_at AT TIME ZONE 'Europe/Moscow', 'HH24:MI') AS time,
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
            WHERE EXTRACT(YEAR FROM l.scheduled_at AT TIME ZONE 'Europe/Moscow') = ?
              AND EXTRACT(MONTH FROM l.scheduled_at AT TIME ZONE 'Europe/Moscow') = ?
            GROUP BY l.id, l.scheduled_at, lang.code, l.status, t.name, r.name
            ORDER BY l.scheduled_at ASC
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, year, month);

        Map<String, String> statusMap = Map.of(
            "DONE",        "done",
            "MISSED",      "missed",
            "PLANNED",     "planned",
            "IN_PROGRESS", "now",
            "CANCELLED",   "missed"
        );

        Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Map<Integer, Integer> lessonsPerDay = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int day = ((Number) row.get("day")).intValue();
            String rawStatus = (String) row.get("status");
            String mappedStatus = statusMap.getOrDefault(rawStatus != null ? rawStatus : "", rawStatus);

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("t", row.get("time"));
            event.put("l", row.get("lang"));
            event.put("s", mappedStatus);
            event.put("who", row.get("teacher_name"));
            event.put("room", row.get("room_name"));
            event.put("students", row.get("student_names"));

            grouped.computeIfAbsent(day, k -> new ArrayList<>()).add(event);
            lessonsPerDay.merge(day, 1, Integer::sum);
        }

        Map<String, Object> days = new LinkedHashMap<>();
        grouped.forEach((k, v) -> days.put(String.valueOf(k), v));

        return ResponseEntity.ok(Map.of(
            "year",   year,
            "month",  month,
            "rooms", List.of(
                Map.of("id", 1, "name", "Zoom — основной"),
                Map.of("id", 2, "name", "Zoom — зал 2"),
                Map.of("id", 3, "name", "Офис · каб. 1")
            ),
            "totalLessons", rows.size(),
            "days", days
        ));
    }
}
