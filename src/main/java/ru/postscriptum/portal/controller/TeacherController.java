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

    @PostMapping("/api/enrollments")
    public ResponseEntity<Void> enroll(@RequestBody EnrollRequest body) {
        String email = currentEmail();
        teacherService.enroll(email, body);
        return ResponseEntity.ok().build();
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
