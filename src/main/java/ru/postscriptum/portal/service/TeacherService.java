package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.postscriptum.portal.dto.EnrollRequest;
import ru.postscriptum.portal.dto.TeacherDto;
import ru.postscriptum.portal.dto.TeacherStudentDto;
import ru.postscriptum.portal.repository.UserRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TeacherService {

    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;

    public List<TeacherDto> listTeachers(String currentUserEmail) {
        String sql = """
            SELECT u.id, u.name, u.initials, u.email,
                   tp.bio, tp.is_native, tp.rating, tp.workload_chip,
                   STRING_AGG(DISTINCT l.code, ',') AS lang_codes,
                   STRING_AGG(DISTINCT l.name_ru || ' ' || lv.code, ',') AS lang_names,
                   (SELECT tl2.language_id FROM teacher_languages tl2 WHERE tl2.teacher_id = u.id AND tl2.is_primary = true LIMIT 1) AS primary_lang_id
            FROM users u
            LEFT JOIN teacher_profiles tp ON tp.user_id = u.id
            LEFT JOIN teacher_languages tl ON tl.teacher_id = u.id
            LEFT JOIN languages l ON l.id = tl.language_id
            LEFT JOIN levels lv ON lv.id = tl.level_id
            WHERE u.role = 'TEACHER' AND u.is_active = true
            GROUP BY u.id, u.name, u.initials, u.email, tp.bio, tp.is_native, tp.rating, tp.workload_chip
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        Long currentStudentId = null;
        if (currentUserEmail != null) {
            try {
                currentStudentId = jdbc.queryForObject(
                    "SELECT id FROM users WHERE email = ? AND role = 'STUDENT'",
                    Long.class, currentUserEmail);
            } catch (Exception e) { /* unauthenticated or not student */ }
        }

        List<TeacherDto> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long teacherId = ((Number) row.get("id")).longValue();

            // primary lang code
            String flag = "fr";
            Object primLangId = row.get("primary_lang_id");
            if (primLangId != null) {
                try {
                    flag = jdbc.queryForObject("SELECT code FROM languages WHERE id = ?", String.class, primLangId);
                } catch (Exception ignore) {}
            }

            // lang names list
            String langNamesStr = (String) row.get("lang_names");
            List<String> langs = langNamesStr != null
                    ? Arrays.asList(langNamesStr.split(","))
                    : List.of();

            // student count
            int students = 0;
            try {
                students = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM enrollments WHERE teacher_id = ?", Integer.class, teacherId);
            } catch (Exception ignore) {}

            // myTeacher + next lesson
            boolean myTeacher = false;
            String next = null;
            if (currentStudentId != null) {
                try {
                    myTeacher = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT COUNT(*) > 0 FROM enrollments WHERE teacher_id = ? AND student_id = ?",
                        Boolean.class, teacherId, currentStudentId));
                } catch (Exception ignore) {}
                if (myTeacher) {
                    try {
                        java.sql.Timestamp ts = jdbc.queryForObject(
                            "SELECT l.scheduled_at FROM lesson_students ls JOIN lessons l ON l.id = ls.lesson_id " +
                            "WHERE ls.student_id = ? AND l.teacher_id = ? AND l.status = 'PLANNED' AND l.scheduled_at > NOW() " +
                            "ORDER BY l.scheduled_at ASC LIMIT 1",
                            java.sql.Timestamp.class, currentStudentId, teacherId);
                        if (ts != null) {
                            var odt = ts.toInstant().atOffset(java.time.ZoneOffset.ofHours(3));
                            String[] DAYS = {"", "ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС"};
                            int dow = odt.getDayOfWeek().getValue();
                            next = DAYS[dow] + " · " + odt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                        }
                    } catch (Exception ignore) {}
                }
            }

            double rating = row.get("rating") != null ? ((Number) row.get("rating")).doubleValue() : 0.0;
            boolean isNative = Boolean.TRUE.equals(row.get("is_native"));
            String subtitle = isNative ? "Преподаватель · носитель" : "Преподаватель";
            String bio = (String) row.get("bio");

            result.add(new TeacherDto(
                teacherId, (String) row.get("name"), (String) row.get("initials"),
                subtitle,
                flag, isNative, langs, rating, 0, students,
                null, bio, next, List.of(), myTeacher,
                (String) row.get("email")
            ));
        }
        return result;
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
