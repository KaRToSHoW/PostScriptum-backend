package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class ReportsController {

    private final JdbcTemplate jdbc;

    private static final String[] MONTH_LABELS = {
        "Январь","Февраль","Март","Апрель","Май","Июнь",
        "Июль","Август","Сентябрь","Октябрь","Ноябрь","Декабрь"
    };

    @GetMapping
    public ResponseEntity<?> reports(
            @RequestParam(defaultValue = "6") int months,
            Authentication auth) {

        if (auth == null) return ResponseEntity.status(401).build();

        LocalDate today = LocalDate.now();

        // ── 1. Помесячная сводка ──────────────────────────────────────────
        List<Map<String, Object>> monthly = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            LocalDate d  = today.minusMonths(i);
            int m        = d.getMonthValue();
            int y        = d.getYear();

            // Уроки: считаем только фактически состоявшиеся (DONE) и пропущенные (MISSED)
            long done   = 0;
            long missed = 0;
            try {
                Map<String, Object> row = jdbc.queryForMap(
                    "SELECT " +
                    "  COUNT(*) FILTER (WHERE status = 'DONE')   AS done, " +
                    "  COUNT(*) FILTER (WHERE status = 'MISSED') AS missed " +
                    "FROM lessons " +
                    "WHERE EXTRACT(MONTH FROM scheduled_at) = ? " +
                    "  AND EXTRACT(YEAR  FROM scheduled_at) = ?",
                    m, y);
                done   = row.get("done")   != null ? ((Number) row.get("done")).longValue()   : 0;
                missed = row.get("missed") != null ? ((Number) row.get("missed")).longValue() : 0;
            } catch (Exception ignored) {}

            long held = done + missed;
            // Посещаемость = проведённые / (проведённые + пропущенные)
            int attendance = held > 0 ? (int) Math.round(done * 100.0 / held) : 0;

            long revenue = 0;
            try {
                Long v = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM payments " +
                    "WHERE status = 'PAID' " +
                    "  AND EXTRACT(MONTH FROM paid_at) = ? " +
                    "  AND EXTRACT(YEAR  FROM paid_at) = ?",
                    Long.class, m, y);
                if (v != null) revenue = v;
            } catch (Exception ignored) {}

            long newStudents = 0;
            try {
                Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users " +
                    "WHERE role = 'STUDENT' " +
                    "  AND EXTRACT(MONTH FROM created_at) = ? " +
                    "  AND EXTRACT(YEAR  FROM created_at) = ?",
                    Long.class, m, y);
                if (v != null) newStudents = v;
            } catch (Exception ignored) {}

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month",       MONTH_LABELS[m - 1]);
            row.put("lessons",     done);      // показываем проведённые
            row.put("attendance",  attendance);
            row.put("revenue",     revenue);
            row.put("newStudents", newStudents);
            monthly.add(row);
        }

        // ── 2. Отчёт по преподавателям ────────────────────────────────────
        // Посещаемость на уровне преподавателя: DONE / (DONE + MISSED)
        List<Map<String, Object>> teachers = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT u.name, " +
                "  COUNT(DISTINCT l.id) FILTER (WHERE l.status = 'DONE')              AS done_lessons, " +
                "  COUNT(DISTINCT l.id) FILTER (WHERE l.status IN ('DONE','MISSED'))  AS held_lessons, " +
                "  COALESCE(tp.rating, 0)                                             AS rating, " +
                "  COUNT(DISTINCT e.student_id)                                       AS students " +
                "FROM users u " +
                "JOIN teacher_profiles tp ON tp.user_id = u.id " +
                "LEFT JOIN lessons l    ON l.teacher_id   = u.id " +
                "LEFT JOIN enrollments e ON e.teacher_id  = u.id AND e.is_active = true " +
                "WHERE u.role = 'TEACHER' AND u.is_active = true " +
                "GROUP BY u.name, tp.rating " +
                "ORDER BY done_lessons DESC",
                rs -> {
                    long done_l = rs.getLong("done_lessons");
                    long held_l = rs.getLong("held_lessons");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name",     rs.getString("name"));
                    row.put("lessons",  held_l);   // всего состоялось (done+missed)
                    row.put("attended", done_l);   // проведено
                    row.put("rating",   rs.getDouble("rating"));
                    row.put("students", rs.getLong("students"));
                    teachers.add(row);
                }
            );
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of(
            "monthly",  monthly,
            "teachers", teachers
        ));
    }
}
