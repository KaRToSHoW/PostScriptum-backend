package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.postscriptum.portal.dto.EnrollRequest;
import ru.postscriptum.portal.dto.TeacherDto;
import ru.postscriptum.portal.dto.TeacherStudentDto;
import ru.postscriptum.portal.model.*;
import ru.postscriptum.portal.repository.UserRepository;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TeacherService {

    private final UserRepository userRepository;

    private static final Map<String, String> LANG_NAME = Map.of(
            "fr", "Французский", "en", "Английский",
            "de", "Немецкий",    "es", "Испанский", "it", "Итальянский"
    );

    public List<TeacherDto> listTeachers(String currentUserEmail) {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole() == UserRole.TEACHER)
                .map(t -> new TeacherDto(
                        t.getId(), t.getName(), t.getInitials(),
                        t.getSubtitle() != null ? t.getSubtitle() : "Преподаватель",
                        "fr", false,
                        List.of(), 0.0, 0, 0, null, null, null, List.of(), false
                ))
                .toList();
    }

    public TeacherDto getTeacher(Long id, String email) {
        return listTeachers(email).stream()
                .filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow();
    }

    public List<TeacherStudentDto> getMyStudents(String teacherEmail) {
        return List.of(); // заполняется после добавления данных в БД
    }

    public void enroll(String studentEmail, EnrollRequest req) {
        // реализуется после создания курсов в БД
    }
}
