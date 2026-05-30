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
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));

                String role = rs.getString("role");
                boolean isNative = rs.getBoolean("is_native");
                String roleLabel = switch (role == null ? "" : role) {
                    case "TEACHER" -> isNative ? "Преподаватель · носитель" : "Преподаватель";
                    case "MANAGER" -> "Менеджер";
                    case "ADMIN"   -> "Админ · основатель";
                    default        -> role;
                };
                row.put("role", roleLabel);

                String chip = switch (role == null ? "" : role) {
                    case "TEACHER" -> "orange";
                    case "MANAGER" -> "blue";
                    case "ADMIN"   -> "purple";
                    default        -> "gray";
                };
                row.put("chip", chip);

                String flag = rs.getString("flag");
                row.put("flag", flag != null ? flag : "");

                int capacityHours = rs.getInt("capacity_hours");
                row.put("capacity", capacityHours > 0 ? capacityHours : 30);
                row.put("weekHours", capacityHours > 0 ? capacityHours + " ч/нед" : "—");

                long studentsCount = rs.getLong("students_count");
                row.put("total", studentsCount);

                // heatmap — static reasonable placeholder (no daily tracking yet)
                row.put("heatmap", List.of(3, 4, 3, 4, 3, 2, 0));

                return row;
            }
        );

        return ResponseEntity.ok(result);
    }

    @GetMapping("/leads")
    public ResponseEntity<?> leads(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());

        List<Map<String, Object>> result = jdbc.query(
            "SELECT le.id, le.name, le.status, " +
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

                String status = rs.getString("status");
                row.put("isNew", "NEW".equals(status));

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
                Map.of("name", "Финансы · все",      "permissions", List.of("—",    "—",    "—",    "—",    "R/W")),
                Map.of("name", "Управление ролями",  "permissions", List.of("—",    "—",    "—",    "—",    "R/W")),
                Map.of("name", "Заявки и лиды",      "permissions", List.of("—",    "—",    "—",    "R/W",  "R/W"))
            )
        ));
    }
}
