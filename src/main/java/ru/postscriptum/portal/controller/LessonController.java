package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.service.TeacherService;

/**
 * Точка входа для подключения к уроку. Сейчас это тонкая заглушка, фиксирующая
 * присутствие звонящего (ученика) — в будущем сюда подключится своя система
 * конференций: при реальном входе в звонок будет дёргаться этот же эндпоинт.
 */
@RestController
@RequestMapping("/api/lessons")
@RequiredArgsConstructor
public class LessonController {

    private final TeacherService teacherService;

    @PostMapping("/{id}/join")
    public ResponseEntity<?> join(@PathVariable long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String email = (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName()))
            ? null : auth.getName();
        return teacherService.joinLesson(email, id);
    }
}
