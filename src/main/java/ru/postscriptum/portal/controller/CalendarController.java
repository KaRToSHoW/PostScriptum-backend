package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.service.CalendarService;

import java.util.*;

@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final JdbcTemplate jdbc;
    private final CalendarService calendar;

    /** Свой календарь: преподаватель видит свои занятия, ученик — занятия, в которые он записан. */
    @GetMapping
    public ResponseEntity<?> student(
            Authentication auth,
            @RequestParam(defaultValue = "2026") int year,
            @RequestParam(defaultValue = "5")    int month) {

        if (auth == null) {
            return ResponseEntity.ok(Map.of("year", year, "month", month, "events", Map.of()));
        }

        Map<String, Object> me;
        try {
            me = jdbc.queryForMap("SELECT id, role::text AS role FROM users WHERE email = ?", auth.getName());
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("year", year, "month", month, "events", Map.of()));
        }
        long id = ((Number) me.get("id")).longValue();

        return ResponseEntity.ok("TEACHER".equals(me.get("role"))
            ? calendar.teacherMonth(id, year, month)
            : calendar.studentMonth(id, year, month));
    }

    @GetMapping("/admin")
    public ResponseEntity<?> admin(
            @RequestParam(defaultValue = "2026") int year,
            @RequestParam(defaultValue = "5")    int month,
            @RequestParam(required = false)      Long teacherId) {
        return ResponseEntity.ok(calendar.adminMonth(year, month, teacherId));
    }
}
