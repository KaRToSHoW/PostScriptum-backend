package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.service.AvailabilityService;

import java.util.List;
import java.util.Map;

/** Окна доступности преподавателя: сам преподаватель редактирует свои, менеджер и администратор — просматривают. */
@RestController
@RequiredArgsConstructor
public class AvailabilityController {

    private final JdbcTemplate jdbc;
    private final AvailabilityService availability;

    @GetMapping("/api/teacher/availability")
    public ResponseEntity<?> myAvailability(Authentication auth) {
        Long teacherId = currentTeacherId(auth);
        if (teacherId == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(availability.list(teacherId));
    }

    /** body: {"windows": [{"weekday": 1, "start": "10:00", "end": "14:00"}, ...]} */
    @PutMapping("/api/teacher/availability")
    public ResponseEntity<?> replaceMyAvailability(Authentication auth, @RequestBody Map<String, Object> body) {
        Long teacherId = currentTeacherId(auth);
        if (teacherId == null) return ResponseEntity.status(403).build();
        Object windows = body.get("windows");
        if (!(windows instanceof List<?> list)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Не передан список окон"));
        }
        try {
            availability.replace(teacherId, list);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message",
                e instanceof IllegalArgumentException ? e.getMessage() : "Некорректные данные окон доступности"));
        }
        return ResponseEntity.ok(availability.list(teacherId));
    }

    /** Для менеджера и администратора (доступ к /api/admin/** ограничен в SecurityConfig). */
    @GetMapping("/api/admin/teachers/{teacherId}/availability")
    public ResponseEntity<?> teacherAvailability(@PathVariable long teacherId) {
        return ResponseEntity.ok(availability.list(teacherId));
    }

    private Long currentTeacherId(Authentication auth) {
        if (auth == null) return null;
        try {
            return jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ? AND role = 'TEACHER'", Long.class, auth.getName());
        } catch (Exception e) {
            return null;
        }
    }
}
