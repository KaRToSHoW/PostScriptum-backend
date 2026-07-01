package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.service.DashboardService;

import java.util.*;

@RestController
@RequestMapping("/api/parent")
@RequiredArgsConstructor
public class ParentController {

    private final JdbcTemplate jdbc;
    private final DashboardService dashboardService;

    /** Дети текущего родителя. */
    @GetMapping("/children")
    public ResponseEntity<?> children(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT u.id, u.name, u.initials, " +
            "       (SELECT COUNT(*) FROM enrollments e WHERE e.student_id=u.id AND e.is_active) AS courses, " +
            "       (SELECT STRING_AGG(DISTINCT l.code, ',') " +
            "          FROM enrollments e JOIN languages l ON l.id = e.language_id " +
            "          WHERE e.student_id=u.id AND e.is_active) AS lang_codes, " +
            "       sp.streak_days " +
            "FROM users u " +
            "JOIN student_profiles sp ON sp.user_id = u.id " +
            "JOIN users me ON me.email = ? " +
            "WHERE sp.parent_id = me.id " +
            "ORDER BY u.name",
            auth.getName());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("id",        row.get("id"));
            child.put("name",      row.get("name"));
            child.put("initials",  row.get("initials"));
            child.put("courses",   row.get("courses") != null ? row.get("courses") : 0);
            child.put("langCodes", row.get("lang_codes") != null ? row.get("lang_codes").toString().split(",") : new String[0]);
            child.put("streak",    row.get("streak_days") != null ? row.get("streak_days") : 0);
            result.add(child);
        }
        return ResponseEntity.ok(result);
    }

    /** Дашборд конкретного ребёнка (только если он привязан к этому родителю). */
    @GetMapping("/child/{id}/dashboard")
    public ResponseEntity<?> childDashboard(Authentication auth, @PathVariable Long id) {
        if (auth == null) return ResponseEntity.status(401).build();

        Integer ok = jdbc.queryForObject(
            "SELECT COUNT(*) FROM student_profiles sp JOIN users me ON me.email=? " +
            "WHERE sp.user_id=? AND sp.parent_id=me.id",
            Integer.class, auth.getName(), id);

        if (ok == null || ok == 0) {
            return ResponseEntity.status(403).body(Map.of("message", "Нет доступа к этому ученику"));
        }

        return ResponseEntity.ok(dashboardService.getStudentDashboard(id));
    }
}
