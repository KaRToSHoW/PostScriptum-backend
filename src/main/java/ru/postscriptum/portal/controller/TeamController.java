package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class TeamController {

    private final JdbcTemplate jdbc;

    @GetMapping("/team")
    public ResponseEntity<?> team(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());

        // Границы текущей недели (Пн 00:00 — след. Пн) в МСК
        java.time.ZoneId msk = java.time.ZoneId.of("Europe/Moscow");
        java.time.LocalDate monday = java.time.LocalDate.now(msk).with(java.time.DayOfWeek.MONDAY);
        Timestamp weekStart = Timestamp.from(monday.atStartOfDay(msk).toInstant());
        Timestamp weekEnd   = Timestamp.from(monday.plusWeeks(1).atStartOfDay(msk).toInstant());

        // Реальная нагрузка за неделю: уроки по дням (Пн..Вс) и суммарные минуты по преподавателям
        final java.util.Map<Long, int[]> lessonsByDay = new java.util.HashMap<>();
        final java.util.Map<Long, Integer> minutesByTeacher = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT teacher_id, " +
                "       EXTRACT(ISODOW FROM (scheduled_at AT TIME ZONE 'Europe/Moscow'))::int AS dow, " +
                "       COUNT(*)::int AS cnt, COALESCE(SUM(duration_min),0)::int AS mins " +
                "FROM lessons WHERE scheduled_at >= ? AND scheduled_at < ? " +
                "GROUP BY teacher_id, dow",
                weekStart, weekEnd)) {
            Long tid = ((Number) r.get("teacher_id")).longValue();
            int dow  = ((Number) r.get("dow")).intValue();      // 1=Пн .. 7=Вс
            int cnt  = ((Number) r.get("cnt")).intValue();
            int mins = ((Number) r.get("mins")).intValue();
            lessonsByDay.computeIfAbsent(tid, k -> new int[7])[dow - 1] = cnt;
            minutesByTeacher.merge(tid, mins, Integer::sum);
        }

        List<Map<String, Object>> result = jdbc.query(
            "SELECT u.id, u.name, u.role, u.initials, " +
            "       tp.capacity_hours, tp.is_native, tp.workload_chip, " +
            "       (SELECT code FROM languages l " +
            "          JOIN teacher_languages tl ON tl.language_id=l.id " +
            "          WHERE tl.teacher_id=u.id AND tl.is_primary LIMIT 1) AS flag, " +
            "       (SELECT COUNT(*) FROM enrollments e WHERE e.teacher_id = u.id) AS students_count " +
            "FROM users u " +
            "LEFT JOIN teacher_profiles tp ON tp.user_id = u.id " +
            "WHERE u.role IN ('TEACHER','MANAGER','ADMIN') AND u.is_active = true " +
            "ORDER BY u.role, u.name",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                long id = rs.getLong("id");
                row.put("id", id);
                row.put("name", rs.getString("name"));

                String role = rs.getString("role");
                row.put("roleType", role);                       // сырое значение для фильтрации на фронте
                boolean isNative = rs.getBoolean("is_native");
                row.put("role", switch (role == null ? "" : role) {
                    case "TEACHER" -> isNative ? "Преподаватель · носитель" : "Преподаватель";
                    case "MANAGER" -> "Менеджер";
                    case "ADMIN"   -> "Админ · основатель";
                    default        -> role;
                });
                row.put("chip", switch (role == null ? "" : role) {
                    case "TEACHER" -> "orange";
                    case "MANAGER" -> "blue";
                    case "ADMIN"   -> "purple";
                    default        -> "gray";
                });

                String flag = rs.getString("flag");
                row.put("flag", flag != null ? flag : "");

                int capacityHours = rs.getInt("capacity_hours");
                int cap = capacityHours > 0 ? capacityHours : 20;
                row.put("capacity", cap);
                row.put("total", rs.getLong("students_count"));

                // реальная недельная нагрузка
                int[] hm = lessonsByDay.getOrDefault(id, new int[7]);
                List<Integer> heat = new ArrayList<>(hm.length);
                for (int v : hm) heat.add(v);
                row.put("heatmap", heat);

                int workedHours = Math.round(minutesByTeacher.getOrDefault(id, 0) / 60f);
                row.put("weekHours", workedHours);
                int pct = cap > 0 ? Math.round(workedHours * 100f / cap) : 0;
                row.put("loadPct", pct);
                row.put("over", pct > 100);

                return row;
            }
        );

        return ResponseEntity.ok(result);
    }

    @GetMapping("/leads")
    public ResponseEntity<?> leads(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());

        List<Map<String, Object>> result = jdbc.query(
            "SELECT le.id, le.name, le.status, le.phone, le.email, le.notes, le.source, " +
            "       COALESCE(lang.code,'') AS lang, " +
            "       CONCAT_WS(' · ', lang.name_ru, lv.code, le.preferred_time, le.frequency) AS details, " +
            "       le.received_at " +
            "FROM leads le " +
            "LEFT JOIN languages lang ON lang.id = le.language_id " +
            "LEFT JOIN levels lv ON lv.id = le.level_id " +
            "ORDER BY le.received_at DESC",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                row.put("details", rs.getString("details"));
                row.put("lang", rs.getString("lang"));
                row.put("phone", rs.getString("phone"));
                row.put("email", rs.getString("email"));
                row.put("comment", rs.getString("notes"));
                row.put("source", rs.getString("source"));

                String status = rs.getString("status");
                row.put("isNew", "NEW".equals(status));
                row.put("status", status);

                Timestamp ts = rs.getTimestamp("received_at");
                row.put("receivedAt", ts != null ? humanizeTime(ts.toLocalDateTime()) : "—");

                return row;
            }
        );

        return ResponseEntity.ok(result);
    }

    private String humanizeTime(LocalDateTime receivedAt) {
        Duration d = Duration.between(receivedAt, LocalDateTime.now());
        long minutes = d.toMinutes();
        if (minutes < 1)  return "только что";
        if (minutes < 60) return minutes + " мин";
        long hours = d.toHours();
        if (hours < 24)   return hours + " ч";
        long days = d.toDays();
        if (days == 1)    return "вчера";
        if (days < 7)     return days + " дн";
        long weeks = days / 7;
        if (weeks < 5)    return weeks + " нед";
        return (days / 30) + " мес";
    }

    @PostMapping("/leads/{id}/status")
    public ResponseEntity<?> updateLeadStatus(@PathVariable long id,
                                               @RequestBody Map<String, String> body,
                                               Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        String status = body.get("status");
        if (status == null || status.isBlank()) return ResponseEntity.badRequest().build();
        jdbc.update("UPDATE leads SET status=?::lead_status WHERE id=?", status, id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/access-matrix")
    public ResponseEntity<?> accessMatrix() {
        return ResponseEntity.ok(Map.of(
            "roles", List.of("Ученик", "Родитель", "Препод.", "Менеджер", "Админ"),
            "modules", List.of(
                Map.of("name", "Расписание · свои",  "permissions", List.of("R",    "R",    "R/W",  "R/W",  "R/W")),
                Map.of("name", "Расписание · все",   "permissions", List.of("—",    "—",    "—",    "R/W",  "R/W")),
                Map.of("name", "Домашние задания",   "permissions", List.of("R/W",  "R",    "R/W",  "—",    "R/W")),
                Map.of("name", "Материалы курса",    "permissions", List.of("R",    "—",    "R/W",  "—",    "R/W")),
                Map.of("name", "Финансы · свои",     "permissions", List.of("R",    "R",    "R",    "—",    "R/W")),
                Map.of("name", "Финансы · все",      "permissions", List.of("—",    "—",    "—",    "R/W",  "R/W")),
                Map.of("name", "Управление ролями",  "permissions", List.of("—",    "—",    "—",    "—",    "R/W")),
                Map.of("name", "Заявки и лиды",      "permissions", List.of("—",    "—",    "—",    "R/W",  "R/W"))
            )
        ));
    }
}
