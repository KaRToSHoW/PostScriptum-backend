package ru.postscriptum.portal.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.dto.*;
import ru.postscriptum.portal.service.AuthService;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        try {
            return ResponseEntity.ok(authService.login(req));
        } catch (DisabledException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Аккаунт отключён. Обратитесь к администратору."));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Неверный email или пароль"));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        try {
            return ResponseEntity.ok(authService.register(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(@AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(authService.me(user.getUsername()));
    }

    /** Сброс пароля по email: { email, newPassword } → новый пароль + вход. */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String email       = body.get("email");
        String newPassword = body.get("newPassword");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Укажите email"));
        }
        if (newPassword == null || newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("message", "Пароль минимум 6 символов"));
        }
        try {
            return ResponseEntity.ok(authService.resetPassword(email.trim(), newPassword));
        } catch (DisabledException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Аккаунт отключён. Обратитесь к администратору."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        }
    }
}
