package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.service.ScheduleSuggestService;

import java.util.*;

/**
 * «Подобрать время» при назначении занятия.
 * body: { teacherId? (для менеджера/админа), studentIds: [..], durationMin, mode: "dates"|"regular",
 *         horizonDays? (разовое, по умолчанию 14), weeks? (регулярное, 8), daysPerWeek? (1|2) }
 */
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleSuggestController {

    private final JdbcTemplate jdbc;
    private final ScheduleSuggestService suggest;

    @PostMapping("/suggest")
    public ResponseEntity<?> suggest(Authentication auth, @RequestBody Map<String, Object> body) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> me = jdbc.queryForMap("SELECT id, role::text AS role FROM users WHERE email = ?", auth.getName());
        long meId = ((Number) me.get("id")).longValue();
        String role = (String) me.get("role");

        List<Long> studentIds = new ArrayList<>();
        if (body.get("studentIds") instanceof List<?> list) {
            for (Object o : list) if (o instanceof Number n) studentIds.add(n.longValue());
        }
        if (studentIds.isEmpty()) return ResponseEntity.badRequest().body(Map.of("message", "Выберите ученика"));
        if (studentIds.size() > 20) return ResponseEntity.badRequest().body(Map.of("message", "Слишком много учеников"));

        long teacherId;
        if ("TEACHER".equals(role)) {
            teacherId = meId;
            // занятость можно смотреть только у своих учеников
            String in = String.join(",", Collections.nCopies(studentIds.size(), "?"));
            List<Object> args = new ArrayList<>(List.of(teacherId));
            args.addAll(studentIds);
            Integer own = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT student_id) FROM enrollments WHERE teacher_id = ? AND is_active AND student_id IN (" + in + ")",
                Integer.class, args.toArray());
            if (own == null || own < studentIds.size()) {
                return ResponseEntity.status(403).body(Map.of("message", "Можно подбирать время только для своих учеников"));
            }
        } else if ("ADMIN".equals(role) || "MANAGER".equals(role)) {
            if (!(body.get("teacherId") instanceof Number n)) return ResponseEntity.badRequest().body(Map.of("message", "Выберите преподавателя"));
            teacherId = n.longValue();
        } else {
            return ResponseEntity.status(403).build();
        }

        int duration = clamp(intOr(body.get("durationMin"), 60), 15, 240);
        int limit = clamp(intOr(body.get("limit"), 6), 1, 12);
        if ("regular".equals(body.get("mode"))) {
            int weeks = clamp(intOr(body.get("weeks"), 8), 1, 26);
            int perWeek = clamp(intOr(body.get("daysPerWeek"), 1), 1, 2);
            return ResponseEntity.ok(suggest.suggestWeekly(teacherId, studentIds, duration, weeks, perWeek, limit));
        }
        int horizon = clamp(intOr(body.get("horizonDays"), 14), 1, 31);
        return ResponseEntity.ok(suggest.suggestDates(teacherId, studentIds, duration, horizon, limit));
    }

    private static int intOr(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
