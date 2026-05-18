package ru.postscriptum.portal.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ru.postscriptum.portal.model.User;
import ru.postscriptum.portal.model.UserRole;
import ru.postscriptum.portal.repository.UserRepository;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) return;

        userRepository.saveAll(List.of(
            User.builder()
                .name("Анна Соколова")
                .email("anna@test.ru")
                .password(passwordEncoder.encode("student123"))
                .role(UserRole.STUDENT)
                .initials("АС")
                .subtitle("Ученик · французский B1")
                .build(),
            User.builder()
                .name("Софья Фролова")
                .email("sofia@test.ru")
                .password(passwordEncoder.encode("teacher123"))
                .role(UserRole.TEACHER)
                .initials("СФ")
                .subtitle("Преподаватель · фр, англ")
                .build(),
            User.builder()
                .name("Михаил Фролов")
                .email("admin@test.ru")
                .password(passwordEncoder.encode("admin123"))
                .role(UserRole.ADMIN)
                .initials("МФ")
                .subtitle("Администратор · Post Scriptum")
                .build()
        ));

        System.out.println("""
            ╔══════════════════════════════════════════╗
            ║  PostScriptum Portal — тестовые аккаунты ║
            ╠══════════════════════════════════════════╣
            ║  anna@test.ru    / student123  (ученик)  ║
            ║  sofia@test.ru   / teacher123  (препод)  ║
            ║  admin@test.ru   / admin123    (админ)   ║
            ╚══════════════════════════════════════════╝
            """);
    }
}
