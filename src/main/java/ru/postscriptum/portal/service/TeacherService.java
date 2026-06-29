package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.postscriptum.portal.dto.EnrollRequest;
import ru.postscriptum.portal.dto.TeacherDto;
import ru.postscriptum.portal.dto.TeacherStudentDto;
import ru.postscriptum.portal.repository.UserRepository;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

            // lang codes list (fr, en, de, ...)
            String langCodesStr = (String) row.get("lang_codes");
            List<String> langCodes = langCodesStr != null
                    ? Arrays.asList(langCodesStr.split(","))
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
                flag, isNative, langs, langCodes, rating, 0, students,
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
        if (teacherEmail == null) return List.of();

        Long teacherId;
        try {
            teacherId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ? AND role = 'TEACHER'", Long.class, teacherEmail);
        } catch (Exception e) {
            return List.of();
        }

        String sql = """
            SELECT e.student_id, u.name, u.initials,
                   lang.name_ru AS language, lang.code AS lang,
                   lv.code AS level, e.progress_pct, e.is_active
            FROM enrollments e
            JOIN users u ON u.id = e.student_id
            JOIN courses c ON c.id = e.course_id
            JOIN languages lang ON lang.id = c.language_id
            LEFT JOIN levels lv ON lv.id = c.level_id
            WHERE e.teacher_id = ?
            ORDER BY u.name
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql, teacherId);

        List<TeacherStudentDto> result = new ArrayList<>();
        String[] DAYS = {"", "ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС"};
        for (Map<String, Object> row : rows) {
            long studentId = ((Number) row.get("student_id")).longValue();

            String nextLesson = null;
            try {
                java.sql.Timestamp ts = jdbc.queryForObject(
                    "SELECT l.scheduled_at FROM lesson_students ls JOIN lessons l ON l.id = ls.lesson_id " +
                    "WHERE ls.student_id = ? AND l.teacher_id = ? AND l.status = 'PLANNED' AND l.scheduled_at > NOW() " +
                    "ORDER BY l.scheduled_at ASC LIMIT 1",
                    java.sql.Timestamp.class, studentId, teacherId);
                if (ts != null) {
                    var odt = ts.toInstant().atOffset(java.time.ZoneOffset.ofHours(3));
                    int dow = odt.getDayOfWeek().getValue();
                    nextLesson = DAYS[dow] + " · " + odt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                }
            } catch (Exception ignore) {}

            boolean isActive = Boolean.TRUE.equals(row.get("is_active"));

            result.add(new TeacherStudentDto(
                studentId,
                (String) row.get("name"),
                (String) row.get("initials"),
                (String) row.get("language"),
                (String) row.get("lang"),
                (String) row.get("level"),
                ((Number) row.get("progress_pct")).intValue(),
                isActive ? "ACTIVE" : "COMPLETED",
                nextLesson
            ));
        }
        return result;
    }

    public void enroll(String studentEmail, EnrollRequest req) {
        // реализуется после создания курсов в БД
    }

    public Map<String, Object> getEarnings(String teacherEmail, String period) {
        if (teacherEmail == null) return Map.of();

        Map<String, Object> teacherRow;
        try {
            teacherRow = jdbc.queryForMap(
                "SELECT u.id, COALESCE(tp.rate_per_lesson, 1500) AS rate " +
                "FROM users u LEFT JOIN teacher_profiles tp ON tp.user_id = u.id " +
                "WHERE u.email = ? AND u.role = 'TEACHER'", teacherEmail);
        } catch (Exception e) {
            return Map.of();
        }
        long teacherId = ((Number) teacherRow.get("id")).longValue();
        long rate = ((Number) teacherRow.get("rate")).longValue();

        String interval = switch (period == null ? "MONTH" : period.toUpperCase()) {
            case "WEEK"    -> "7 days";
            case "QUARTER" -> "3 months";
            case "YEAR"    -> "1 year";
            default        -> "1 month";
        };

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT l.id, l.scheduled_at, l.status, lang.code AS lang, lang.name_ru AS lang_name,
                   STRING_AGG(DISTINCT s.name, ', ') AS students
            FROM lessons l
            JOIN languages lang ON lang.id = l.language_id
            LEFT JOIN lesson_students ls ON ls.lesson_id = l.id
            LEFT JOIN users s ON s.id = ls.student_id
            WHERE l.teacher_id = ?
              AND l.scheduled_at >= NOW() - ?::interval
              AND l.scheduled_at <= NOW()
            GROUP BY l.id, l.scheduled_at, l.status, lang.code, lang.name_ru
            ORDER BY l.scheduled_at DESC
            """, teacherId, interval);

        List<Map<String, Object>> lessons = new ArrayList<>();
        long completedCount = 0, missedCount = 0, plannedCount = 0;
        long earned = 0;
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

        for (Map<String, Object> row : rows) {
            String status = (String) row.get("status");
            if ("DONE".equals(status)) { completedCount++; earned += rate; }
            else if ("MISSED".equals(status) || "CANCELLED".equals(status)) missedCount++;
            else plannedCount++;

            Timestamp ts = (Timestamp) row.get("scheduled_at");
            Map<String, Object> lesson = new LinkedHashMap<>();
            lesson.put("id", row.get("id"));
            lesson.put("date", ts != null ? ts.toInstant().atOffset(ZoneOffset.ofHours(3)).format(dtf) : "—");
            lesson.put("lang", row.get("lang"));
            lesson.put("langName", row.get("lang_name"));
            lesson.put("students", row.get("students"));
            lesson.put("status", status);
            lesson.put("amount", "DONE".equals(status) ? rate : 0);
            lessons.add(lesson);
        }

        List<Map<String, Object>> kpi = List.of(
            Map.of("l", "Доход за период", "v", "₽ " + String.format("%,d", earned).replace(',', ' '), "d", period(period)),
            Map.of("l", "Проведено уроков", "v", String.valueOf(completedCount), "d", "оплачено"),
            Map.of("l", "Ставка за урок", "v", "₽ " + rate, "d", "текущая"),
            Map.of("l", "Пропущено/отменено", "v", String.valueOf(missedCount), "d", "за период")
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period == null ? "MONTH" : period.toUpperCase());
        result.put("rate", rate);
        result.put("kpi", kpi);
        result.put("lessons", lessons);
        return result;
    }

    private String period(String period) {
        return switch (period == null ? "MONTH" : period.toUpperCase()) {
            case "WEEK"    -> "за неделю";
            case "QUARTER" -> "за квартал";
            case "YEAR"    -> "за год";
            default        -> "за месяц";
        };
    }

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    /** Разовое занятие — преподаватель и ученик договорились на конкретную дату/время. */
    public ResponseEntity<?> createLesson(String teacherEmail, Map<String, Object> body) {
        if (teacherEmail == null) return ResponseEntity.status(401).build();

        Long teacherId = resolveTeacherId(teacherEmail);
        if (teacherId == null) return ResponseEntity.status(403).build();

        Object studentIdObj = body.get("studentId");
        String scheduledAtStr = (String) body.get("scheduledAt");
        if (studentIdObj == null || scheduledAtStr == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Не указан ученик или время занятия"));
        }
        long studentId = ((Number) studentIdObj).longValue();
        int durationMin = body.get("durationMin") != null ? ((Number) body.get("durationMin")).intValue() : 60;

        Map<String, Object> enrollment = resolveEnrollment(teacherId, studentId);
        if (enrollment == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "У этого ученика нет активного курса с вами"));
        }

        ZonedDateTime scheduledAt;
        try {
            scheduledAt = parseDateTime(scheduledAtStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Некорректный формат времени"));
        }

        long lessonId = insertLesson(teacherId, enrollment, scheduledAt, durationMin);
        notifyStudent(studentId, "Назначено новое занятие",
            "У вас занятие " + scheduledAt.format(DateTimeFormatter.ofPattern("dd.MM в HH:mm")));

        return ResponseEntity.ok(Map.of("id", lessonId));
    }

    /** Регулярные занятия — каждую неделю в одно и то же время на несколько недель вперёд. */
    public ResponseEntity<?> createRecurringLessons(String teacherEmail, Map<String, Object> body) {
        if (teacherEmail == null) return ResponseEntity.status(401).build();

        Long teacherId = resolveTeacherId(teacherEmail);
        if (teacherId == null) return ResponseEntity.status(403).build();

        Object studentIdObj = body.get("studentId");
        Object dayOfWeekObj = body.get("dayOfWeek");   // 1=ПН ... 7=ВС
        String time = (String) body.get("time");        // "HH:mm"
        if (studentIdObj == null || dayOfWeekObj == null || time == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Не указан ученик, день недели или время"));
        }
        long studentId = ((Number) studentIdObj).longValue();
        int dayOfWeek = ((Number) dayOfWeekObj).intValue();
        int durationMin = body.get("durationMin") != null ? ((Number) body.get("durationMin")).intValue() : 60;
        int weeksCount = body.get("weeksCount") != null ? ((Number) body.get("weeksCount")).intValue() : 8;

        Map<String, Object> enrollment = resolveEnrollment(teacherId, studentId);
        if (enrollment == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "У этого ученика нет активного курса с вами"));
        }

        LocalTime lt = LocalTime.parse(time);
        LocalDate firstDate = LocalDate.now(MOSCOW).with(DayOfWeek.of(dayOfWeek));
        if (firstDate.isBefore(LocalDate.now(MOSCOW)) ||
            (firstDate.isEqual(LocalDate.now(MOSCOW)) && LocalTime.now(MOSCOW).isAfter(lt))) {
            firstDate = firstDate.plusWeeks(1);
        }

        List<Long> createdIds = new ArrayList<>();
        for (int i = 0; i < weeksCount; i++) {
            ZonedDateTime scheduledAt = firstDate.plusWeeks(i).atTime(lt).atZone(MOSCOW);
            createdIds.add(insertLesson(teacherId, enrollment, scheduledAt, durationMin));
        }

        String[] DAYS_RU = {"", "понедельникам", "вторникам", "средам", "четвергам", "пятницам", "субботам", "воскресеньям"};
        notifyStudent(studentId, "Назначены регулярные занятия",
            "Каждую неделю по " + DAYS_RU[dayOfWeek] + " в " + time + " — на " + weeksCount + " недель вперёд");

        return ResponseEntity.ok(Map.of("createdCount", createdIds.size(), "ids", createdIds));
    }

    private Long resolveTeacherId(String teacherEmail) {
        try {
            return jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ? AND role = 'TEACHER'", Long.class, teacherEmail);
        } catch (Exception e) {
            return null;
        }
    }

    /** Находит активную запись на курс у ученика с этим преподавателем (для language_id/enrollment_id). */
    private Map<String, Object> resolveEnrollment(long teacherId, long studentId) {
        try {
            return jdbc.queryForMap("""
                SELECT e.id AS enrollment_id, c.language_id
                FROM enrollments e
                JOIN courses c ON c.id = e.course_id
                WHERE e.teacher_id = ? AND e.student_id = ? AND e.is_active = true
                ORDER BY e.id DESC LIMIT 1
                """, teacherId, studentId);
        } catch (Exception e) {
            return null;
        }
    }

    private long insertLesson(long teacherId, Map<String, Object> enrollment, ZonedDateTime scheduledAt, int durationMin) {
        long enrollmentId = ((Number) enrollment.get("enrollment_id")).longValue();
        int languageId = ((Number) enrollment.get("language_id")).intValue();

        Long lessonId = jdbc.queryForObject("""
            INSERT INTO lessons (enrollment_id, teacher_id, language_id, format, scheduled_at, duration_min, status, created_at)
            VALUES (?, ?, ?, 'INDIVIDUAL'::lesson_format, ?, ?, 'PLANNED'::lesson_status, NOW())
            RETURNING id
            """, Long.class, enrollmentId, teacherId, languageId, Timestamp.from(scheduledAt.toInstant()), durationMin);

        long studentIdForLesson = jdbc.queryForObject(
            "SELECT student_id FROM enrollments WHERE id = ?", Long.class, enrollmentId);

        jdbc.update(
            "INSERT INTO lesson_students (lesson_id, student_id) VALUES (?, ?)",
            lessonId, studentIdForLesson);

        return lessonId;
    }

    private void notifyStudent(long studentId, String title, String text) {
        jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            VALUES (?, 'LESSON_REMINDER'::notification_type, ?, ?, '/calendar', false, NOW())
            """, studentId, title, text);
    }

    private ZonedDateTime parseDateTime(String iso) {
        try {
            return ZonedDateTime.parse(iso);
        } catch (Exception e) {
            return java.time.LocalDateTime.parse(iso).atZone(MOSCOW);
        }
    }
}
