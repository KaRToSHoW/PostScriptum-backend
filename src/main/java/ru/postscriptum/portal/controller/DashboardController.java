package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.model.User;
import ru.postscriptum.portal.repository.UserRepository;
import ru.postscriptum.portal.service.DashboardService;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final UserRepository userRepository;

    @GetMapping("/student")
    public ResponseEntity<?> student(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(Map.of());
        User user = userRepository.findByEmail(auth.getName()).orElseThrow();
        return ResponseEntity.ok(dashboardService.getStudentDashboard(user.getId()));
    }

    @GetMapping("/teacher")
    public ResponseEntity<?> teacher(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(Map.of());
        User user = userRepository.findByEmail(auth.getName()).orElseThrow();
        return ResponseEntity.ok(dashboardService.getTeacherDashboard(user.getId()));
    }
}
