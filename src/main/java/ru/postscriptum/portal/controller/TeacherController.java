package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.dto.EnrollRequest;
import ru.postscriptum.portal.dto.TeacherDto;
import ru.postscriptum.portal.dto.TeacherStudentDto;
import ru.postscriptum.portal.service.TeacherService;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class TeacherController {

    private final TeacherService teacherService;

    @GetMapping("/api/teachers")
    public ResponseEntity<List<TeacherDto>> listTeachers() {
        String email = currentEmail();
        return ResponseEntity.ok(teacherService.listTeachers(email));
    }

    @GetMapping("/api/teachers/{id}")
    public ResponseEntity<TeacherDto> getTeacher(@PathVariable Long id) {
        String email = currentEmail();
        return ResponseEntity.ok(teacherService.getTeacher(id, email));
    }

    @GetMapping("/api/teacher/students")
    public ResponseEntity<List<TeacherStudentDto>> getMyStudents() {
        String email = currentEmail();
        return ResponseEntity.ok(teacherService.getMyStudents(email));
    }

    @GetMapping("/api/teacher/earnings")
    public ResponseEntity<?> getEarnings(@RequestParam(defaultValue = "MONTH") String period) {
        String email = currentEmail();
        return ResponseEntity.ok(teacherService.getEarnings(email, period));
    }

    @PostMapping("/api/enrollments")
    public ResponseEntity<Void> enroll(@RequestBody EnrollRequest body) {
        String email = currentEmail();
        teacherService.enroll(email, body);
        return ResponseEntity.ok().build();
    }

    // ── Разовое занятие: договорились на конкретное время ────────────────────
    @PostMapping("/api/teacher/lessons")
    public ResponseEntity<?> createLesson(@RequestBody Map<String, Object> body) {
        String email = currentEmail();
        return teacherService.createLesson(email, body);
    }

    // ── Регулярные занятия: каждую неделю в одно и то же время ────────────────
    @PostMapping("/api/teacher/lessons/recurring")
    public ResponseEntity<?> createRecurringLessons(@RequestBody Map<String, Object> body) {
        String email = currentEmail();
        return teacherService.createRecurringLessons(email, body);
    }

    // ── Занятия по конкретным датам (выбраны вручную в календаре) ────────────
    @PostMapping("/api/teacher/lessons/batch")
    public ResponseEntity<?> createBatchLessons(@RequestBody Map<String, Object> body) {
        String email = currentEmail();
        return teacherService.createBatchLessons(email, body);
    }

    // ── Отмена урока (правило 4 часов) ────────────────────────────────────────
    @PostMapping("/api/teacher/lessons/{id}/cancel")
    public ResponseEntity<?> cancelLesson(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        String email = currentEmail();
        return teacherService.cancelLesson(email, id, body != null ? body : Map.of());
    }

    // ── Перенос урока на другое время ─────────────────────────────────────────
    @PostMapping("/api/teacher/lessons/{id}/reschedule")
    public ResponseEntity<?> rescheduleLesson(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String email = currentEmail();
        return teacherService.rescheduleLesson(email, id, body);
    }

    // ── Список учеников урока с отметкой посещения ────────────────────────────
    @GetMapping("/api/teacher/lessons/{id}/roster")
    public ResponseEntity<?> getRoster(@PathVariable long id) {
        String email = currentEmail();
        return teacherService.getLessonRoster(email, id);
    }

    // ── Ручная отметка посещаемости преподавателем ────────────────────────────
    @PostMapping("/api/teacher/lessons/{id}/attendance")
    public ResponseEntity<?> markAttendance(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String email = currentEmail();
        return teacherService.markAttendance(email, id, body);
    }

    // ----------------------------------------------------------------

    private String currentEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth.getName();
    }
}
