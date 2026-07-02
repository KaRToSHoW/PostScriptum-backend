package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.postscriptum.portal.dto.*;
import ru.postscriptum.portal.model.*;
import ru.postscriptum.portal.repository.UserRepository;
import ru.postscriptum.portal.security.JwtTokenProvider;

import java.util.Arrays;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository       userRepository;
    private final PasswordEncoder      passwordEncoder;
    private final AuthenticationManager authManager;
    private final JwtTokenProvider     tokenProvider;

    public AuthResponse login(LoginRequest req) {
        authManager.authenticate(
            new UsernamePasswordAuthenticationToken(req.email(), req.password()));

        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));
        return toResponse(user);
    }

    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Email уже используется");
        }

        String initials = Arrays.stream(req.name().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .map(s -> String.valueOf(s.charAt(0)).toUpperCase())
                .collect(Collectors.joining());

        User user = userRepository.save(User.builder()
                .name(req.name())
                .email(req.email())
                .password(passwordEncoder.encode(req.password()))
                .role(UserRole.STUDENT)
                .initials(initials)
                .timezone("Europe/Moscow")
                .locale("ru")
                .active(true)
                .build());

        return toResponse(user);
    }

    public AuthResponse me(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));
        if (!user.isActive()) throw new RuntimeException("Аккаунт отключён");
        return toResponse(user);
    }

    private AuthResponse toResponse(User user) {
        String subtitle = switch (user.getRole()) {
            case STUDENT  -> "Ученик";
            case TEACHER  -> "Преподаватель";
            case PARENT   -> "Родитель";
            case MANAGER  -> "Менеджер";
            case ADMIN    -> "Администратор";
        };
        return new AuthResponse(
                tokenProvider.generateToken(user),
                user.getRole().name().toLowerCase(),
                user.getName(),
                user.getInitials(),
                subtitle
        );
    }
}
