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
    private final MessageCryptoService crypto;

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
            SELECT e.student_id, u.name, u.initials, u.email,
                   STRING_AGG(lang.name_ru, ',' ORDER BY lang.name_ru) AS languages,
                   STRING_AGG(lang.code, ',' ORDER BY lang.name_ru) AS lang_codes,
                   bool_or(e.is_active) AS is_active
            FROM enrollments e
            JOIN users u ON u.id = e.student_id
            JOIN languages lang ON lang.id = e.language_id
            WHERE e.teacher_id = ?
            GROUP BY e.student_id, u.name, u.initials, u.email
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

            Integer lessonsLeft = null;
            try {
                lessonsLeft = jdbc.queryForObject("""
                    SELECT (lessons_total - lessons_used)
                    FROM subscriptions
                    WHERE student_id = ? AND status = 'ACTIVE'
                    ORDER BY created_at DESC LIMIT 1
                    """, Integer.class, studentId);
            } catch (Exception ignore) {}

            result.add(new TeacherStudentDto(
                studentId,
                (String) row.get("name"),
                (String) row.get("initials"),
                (String) row.get("email"),
                splitOrEmpty((String) row.get("languages")),
                splitOrEmpty((String) row.get("lang_codes")),
                isActive ? "ACTIVE" : "COMPLETED",
                nextLesson,
                lessonsLeft
            ));
        }
        return result;
    }

    private static List<String> splitOrEmpty(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.asList(s.split(","));
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
        String languageCode = (String) body.get("languageCode");

        Map<String, Object> enrollment = resolveEnrollment(teacherId, studentId, languageCode);
        if (enrollment == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "У этого ученика нет активного курса с вами"));
        }

        ZonedDateTime scheduledAt;
        try {
            scheduledAt = parseDateTime(scheduledAtStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Некорректный формат времени"));
        }

        if (hasConflict(teacherId, scheduledAt, durationMin, null)) {
            return ResponseEntity.badRequest().body(Map.of("message", "На это время у вас уже есть урок — выберите другое время"));
        }

        long lessonId = insertLesson(teacherId, enrollment, scheduledAt, durationMin);
        notifyStudent(studentId, "Назначено новое занятие",
            "У вас занятие " + scheduledAt.format(DateTimeFormatter.ofPattern("dd.MM в HH:mm")));

        return ResponseEntity.ok(Map.of("id", lessonId));
    }

    /** Есть ли у преподавателя уже урок, пересекающийся по времени с [start, start+durationMin). */
    private boolean hasConflict(long teacherId, ZonedDateTime start, int durationMin, Long excludeLessonId) {
        Timestamp startTs = Timestamp.from(start.toInstant());
        Integer conflicts;
        if (excludeLessonId == null) {
            conflicts = jdbc.queryForObject("""
                SELECT COUNT(*) FROM lessons
                WHERE teacher_id = ? AND status = 'PLANNED'::lesson_status
                  AND scheduled_at < (?::timestamptz + (? || ' minutes')::interval)
                  AND (scheduled_at + (duration_min || ' minutes')::interval) > ?::timestamptz
                """, Integer.class, teacherId, startTs, durationMin, startTs);
        } else {
            conflicts = jdbc.queryForObject("""
                SELECT COUNT(*) FROM lessons
                WHERE teacher_id = ? AND status = 'PLANNED'::lesson_status AND id != ?
                  AND scheduled_at < (?::timestamptz + (? || ' minutes')::interval)
                  AND (scheduled_at + (duration_min || ' minutes')::interval) > ?::timestamptz
                """, Integer.class, teacherId, excludeLessonId, startTs, durationMin, startTs);
        }
        return conflicts != null && conflicts > 0;
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
        String languageCode = (String) body.get("languageCode");

        Map<String, Object> enrollment = resolveEnrollment(teacherId, studentId, languageCode);
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
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < weeksCount; i++) {
            ZonedDateTime scheduledAt = firstDate.plusWeeks(i).atTime(lt).atZone(MOSCOW);
            if (hasConflict(teacherId, scheduledAt, durationMin, null)) {
                skipped.add(scheduledAt.format(DateTimeFormatter.ofPattern("dd.MM")));
                continue;
            }
            createdIds.add(insertLesson(teacherId, enrollment, scheduledAt, durationMin));
        }

        if (createdIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "На все эти даты у вас уже есть уроки — выберите другое время"));
        }

        String[] DAYS_RU = {"", "понедельникам", "вторникам", "средам", "четвергам", "пятницам", "субботам", "воскресеньям"};
        notifyStudent(studentId, "Назначены регулярные занятия",
            "Каждую неделю по " + DAYS_RU[dayOfWeek] + " в " + time + " — на " + weeksCount + " недель вперёд");

        return ResponseEntity.ok(Map.of("createdCount", createdIds.size(), "ids", createdIds, "skipped", skipped));
    }

    /** Занятия по конкретным датам — преподаватель выбрал даты вручную в календаре. */
    public ResponseEntity<?> createBatchLessons(String teacherEmail, Map<String, Object> body) {
        if (teacherEmail == null) return ResponseEntity.status(401).build();
        Long teacherId = resolveTeacherId(teacherEmail);
        if (teacherId == null) return ResponseEntity.status(403).build();

        Object studentIdObj = body.get("studentId");
        @SuppressWarnings("unchecked")
        List<String> dates = (List<String>) body.get("dates");  // ["YYYY-MM-DD", ...]
        String time = (String) body.get("time");
        if (studentIdObj == null || dates == null || dates.isEmpty() || time == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Не указан ученик, даты или время"));
        }
        long studentId = ((Number) studentIdObj).longValue();
        int durationMin = body.get("durationMin") != null ? ((Number) body.get("durationMin")).intValue() : 60;
        String languageCode = (String) body.get("languageCode");

        Map<String, Object> enrollment = resolveEnrollment(teacherId, studentId, languageCode);
        if (enrollment == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "У этого ученика нет активного курса с вами"));
        }

        LocalTime lt = LocalTime.parse(time);
        List<Long> createdIds = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String dateStr : dates) {
            LocalDate date;
            try {
                date = LocalDate.parse(dateStr);
            } catch (Exception e) {
                skipped.add(dateStr);
                continue;
            }
            ZonedDateTime scheduledAt = date.atTime(lt).atZone(MOSCOW);
            if (hasConflict(teacherId, scheduledAt, durationMin, null)) {
                skipped.add(date.format(DateTimeFormatter.ofPattern("dd.MM")));
                continue;
            }
            createdIds.add(insertLesson(teacherId, enrollment, scheduledAt, durationMin));
        }

        if (createdIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "На все выбранные даты у вас уже есть уроки"));
        }

        notifyStudent(studentId, "Назначены занятия",
            "Назначено " + createdIds.size() + " занятий по выбранным датам");
        return ResponseEntity.ok(Map.of("createdCount", createdIds.size(), "ids", createdIds, "skipped", skipped));
    }

    private Long resolveTeacherId(String teacherEmail) {
        try {
            return jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ? AND role = 'TEACHER'", Long.class, teacherEmail);
        } catch (Exception e) {
            return null;
        }
    }

    /** Находит активную запись у ученика с этим преподавателем, опционально фильтруя по language code. */
    private Map<String, Object> resolveEnrollment(long teacherId, long studentId) {
        return resolveEnrollment(teacherId, studentId, null);
    }

    private Map<String, Object> resolveEnrollment(long teacherId, long studentId, String languageCode) {
        try {
            if (languageCode != null && !languageCode.isBlank()) {
                return jdbc.queryForMap("""
                    SELECT e.id AS enrollment_id, e.language_id
                    FROM enrollments e
                    JOIN languages l ON l.id = e.language_id
                    WHERE e.teacher_id = ? AND e.student_id = ? AND l.code = ? AND e.is_active = true
                    ORDER BY e.id DESC LIMIT 1
                    """, teacherId, studentId, languageCode);
            }
            return jdbc.queryForMap("""
                SELECT e.id AS enrollment_id, e.language_id
                FROM enrollments e
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

    private static final long CANCEL_FREE_WINDOW_HOURS = 4;

    /** Отмена занятия преподавателем. Если до урока меньше 4 часов — урок считается проведённым (списывается). */
    public ResponseEntity<?> cancelLesson(String teacherEmail, long lessonId, Map<String, Object> body) {
        if (teacherEmail == null) return ResponseEntity.status(401).build();
        Long teacherId = resolveTeacherId(teacherEmail);
        if (teacherId == null) return ResponseEntity.status(403).build();

        Map<String, Object> lesson;
        try {
            lesson = jdbc.queryForMap("""
                SELECT l.id, l.scheduled_at, l.status, l.enrollment_id, e.student_id
                FROM lessons l JOIN enrollments e ON e.id = l.enrollment_id
                WHERE l.id = ? AND l.teacher_id = ?
                """, lessonId, teacherId);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("message", "Урок не найден"));
        }

        String status = (String) lesson.get("status");
        if (!"PLANNED".equals(status)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Можно отменить только запланированный урок"));
        }

        Timestamp scheduledAt = (Timestamp) lesson.get("scheduled_at");
        long studentId = ((Number) lesson.get("student_id")).longValue();
        String reason = (String) body.get("reason");

        long hoursUntil = java.time.Duration.between(java.time.Instant.now(), scheduledAt.toInstant()).toHours();
        boolean lateCancel = hoursUntil < CANCEL_FREE_WINDOW_HOURS;

        String cancelNote = (reason != null && !reason.isBlank() ? reason : "Отменено преподавателем")
            + (lateCancel ? " (отмена менее чем за 4 часа — урок списан)" : "");

        jdbc.update(
            "UPDATE lessons SET status='CANCELLED'::lesson_status, cancel_reason=? WHERE id=?",
            cancelNote, lessonId);

        if (lateCancel) {
            jdbc.update("""
                UPDATE subscriptions SET lessons_used = lessons_used + 1
                WHERE id = (
                    SELECT s.id FROM subscriptions s
                    WHERE s.student_id = ? AND s.status = 'ACTIVE'
                    ORDER BY s.created_at DESC LIMIT 1
                )
                """, studentId);
        }

        var odt = scheduledAt.toInstant().atZone(MOSCOW);
        String when = odt.format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"));
        String chatText = lateCancel
            ? "Урок " + when + " отменён. Так как отмена произошла менее чем за 4 часа до начала, урок засчитан как проведённый."
            : "Урок " + when + " отменён без списания — отмена больше чем за 4 часа до начала.";

        notifyStudent(studentId, "Урок отменён", chatText);
        postChatMessage(teacherId, studentId, chatText);

        return ResponseEntity.ok(Map.of("lateCancel", lateCancel));
    }

    /** Перенос занятия на другое (свободное у преподавателя) время. */
    public ResponseEntity<?> rescheduleLesson(String teacherEmail, long lessonId, Map<String, Object> body) {
        if (teacherEmail == null) return ResponseEntity.status(401).build();
        Long teacherId = resolveTeacherId(teacherEmail);
        if (teacherId == null) return ResponseEntity.status(403).build();

        String newAtStr = (String) body.get("scheduledAt");
        if (newAtStr == null) return ResponseEntity.badRequest().body(Map.of("message", "Не указано новое время"));

        Map<String, Object> lesson;
        try {
            lesson = jdbc.queryForMap("""
                SELECT l.id, l.scheduled_at, l.status, l.duration_min, l.original_date, e.student_id
                FROM lessons l JOIN enrollments e ON e.id = l.enrollment_id
                WHERE l.id = ? AND l.teacher_id = ?
                """, lessonId, teacherId);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("message", "Урок не найден"));
        }

        if (!"PLANNED".equals(lesson.get("status"))) {
            return ResponseEntity.badRequest().body(Map.of("message", "Можно перенести только запланированный урок"));
        }

        ZonedDateTime newAt;
        try {
            newAt = parseDateTime(newAtStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Некорректный формат времени"));
        }

        int durationMin = ((Number) lesson.get("duration_min")).intValue();
        Timestamp newTs = Timestamp.from(newAt.toInstant());

        // проверяем, что новый слот свободен у преподавателя (нет пересечения с другими уроками)
        Integer conflicts = jdbc.queryForObject("""
            SELECT COUNT(*) FROM lessons
            WHERE teacher_id = ? AND id != ? AND status = 'PLANNED'
              AND scheduled_at < (?::timestamptz + (? || ' minutes')::interval)
              AND (scheduled_at + (duration_min || ' minutes')::interval) > ?::timestamptz
            """, Integer.class, teacherId, lessonId, newTs, durationMin, newTs);
        if (conflicts != null && conflicts > 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "На это время у вас уже есть урок"));
        }

        Object originalDate = lesson.get("original_date");
        Timestamp keepOriginal = originalDate != null ? (Timestamp) originalDate : (Timestamp) lesson.get("scheduled_at");

        jdbc.update("""
            UPDATE lessons SET scheduled_at=?, original_date=?, reschedule_count=reschedule_count+1, last_rescheduled_at=NOW()
            WHERE id=?
            """, newTs, keepOriginal, lessonId);

        long studentId = ((Number) lesson.get("student_id")).longValue();
        String when = newAt.format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"));
        String chatText = "Урок перенесён на " + when + ".";

        notifyStudent(studentId, "Урок перенесён", chatText);
        postChatMessage(teacherId, studentId, chatText);

        return ResponseEntity.ok(Map.of("scheduledAt", newTs.toString()));
    }

    /** Список учеников урока с текущей отметкой о посещении (для ручной простановки преподавателем). */
    public ResponseEntity<?> getLessonRoster(String teacherEmail, long lessonId) {
        if (teacherEmail == null) return ResponseEntity.status(401).build();
        Long teacherId = resolveTeacherId(teacherEmail);
        if (teacherId == null) return ResponseEntity.status(403).build();

        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM lessons WHERE id=? AND teacher_id=?", Integer.class, lessonId, teacherId);
        if (owns == null || owns == 0) return ResponseEntity.status(404).body(Map.of("message", "Урок не найден"));

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.id, u.name, u.initials, ls.attended
            FROM lesson_students ls JOIN users u ON u.id = ls.student_id
            WHERE ls.lesson_id = ?
            ORDER BY u.name
            """, lessonId);

        List<Map<String, Object>> roster = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("studentId", row.get("id"));
            item.put("name", row.get("name"));
            item.put("initials", row.get("initials"));
            item.put("attended", row.get("attended"));
            roster.add(item);
        }
        return ResponseEntity.ok(roster);
    }

    /** Преподаватель вручную отмечает, кто из учеников был на уроке. Перерешает статус урока сразу же. */
    public ResponseEntity<?> markAttendance(String teacherEmail, long lessonId, Map<String, Object> body) {
        if (teacherEmail == null) return ResponseEntity.status(401).build();
        Long teacherId = resolveTeacherId(teacherEmail);
        if (teacherId == null) return ResponseEntity.status(403).build();

        Integer owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM lessons WHERE id=? AND teacher_id=?", Integer.class, lessonId, teacherId);
        if (owns == null || owns == 0) return ResponseEntity.status(404).body(Map.of("message", "Урок не найден"));

        Object recordsObj = body.get("records");
        if (!(recordsObj instanceof List<?> records)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Не указан список учеников"));
        }

        for (Object recObj : records) {
            if (!(recObj instanceof Map<?, ?> rec)) continue;
            long studentId = ((Number) rec.get("studentId")).longValue();
            boolean attended = Boolean.TRUE.equals(rec.get("attended"));
            jdbc.update(
                "UPDATE lesson_students SET attended=? WHERE lesson_id=? AND student_id=?",
                attended, lessonId, studentId);
        }

        resolveLessonStatus(lessonId);
        return ResponseEntity.ok().build();
    }

    /** Подключение к уроку (заглушка под будущую систему конференций) — отмечает присутствие звонящего. */
    public ResponseEntity<?> joinLesson(String userEmail, long lessonId) {
        if (userEmail == null) return ResponseEntity.status(401).build();

        Long userId;
        String role;
        try {
            Map<String, Object> u = jdbc.queryForMap("SELECT id, role FROM users WHERE email=?", userEmail);
            userId = ((Number) u.get("id")).longValue();
            role = (String) u.get("role");
        } catch (Exception e) {
            return ResponseEntity.status(401).build();
        }

        Integer isParticipant;
        if ("TEACHER".equals(role)) {
            isParticipant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lessons WHERE id=? AND teacher_id=?", Integer.class, lessonId, userId);
        } else {
            isParticipant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lesson_students WHERE lesson_id=? AND student_id=?", Integer.class, lessonId, userId);
            if (isParticipant != null && isParticipant > 0) {
                jdbc.update("UPDATE lesson_students SET attended=true WHERE lesson_id=? AND student_id=?", lessonId, userId);
            }
        }
        if (isParticipant == null || isParticipant == 0) {
            return ResponseEntity.status(403).body(Map.of("message", "Вы не участник этого урока"));
        }

        jdbc.update(
            "UPDATE lessons SET status='IN_PROGRESS'::lesson_status WHERE id=? AND status='PLANNED'::lesson_status",
            lessonId);

        return ResponseEntity.ok().build();
    }

    /** Пересчитывает статус урока по факту посещений (вызывается сразу после ручной отметки). */
    private void resolveLessonStatus(long lessonId) {
        Integer anyAttended = jdbc.queryForObject(
            "SELECT COUNT(*) FROM lesson_students WHERE lesson_id=? AND attended=true", Integer.class, lessonId);
        Integer anyMarked = jdbc.queryForObject(
            "SELECT COUNT(*) FROM lesson_students WHERE lesson_id=? AND attended IS NOT NULL", Integer.class, lessonId);

        if (anyAttended != null && anyAttended > 0) {
            jdbc.update("UPDATE lessons SET status='DONE'::lesson_status WHERE id=? AND status != 'CANCELLED'::lesson_status", lessonId);
        } else if (anyMarked != null && anyMarked > 0) {
            jdbc.update("UPDATE lessons SET status='MISSED'::lesson_status WHERE id=? AND status != 'CANCELLED'::lesson_status", lessonId);
        }
    }

    /** Находит (или создаёт) личный диалог преподавателя с учеником и пишет туда сообщение от его имени. */
    private void postChatMessage(long teacherId, long studentId, String text) {
        List<Long> existing = jdbc.queryForList("""
            SELECT cm.conversation_id FROM conversation_members cm
            WHERE cm.user_id IN (?, ?)
            GROUP BY cm.conversation_id
            HAVING COUNT(DISTINCT cm.user_id) = 2
               AND COUNT(*) = (SELECT COUNT(*) FROM conversation_members x WHERE x.conversation_id = cm.conversation_id)
            """, Long.class, teacherId, studentId);

        long convId;
        if (!existing.isEmpty()) {
            convId = existing.get(0);
        } else {
            convId = jdbc.queryForObject("INSERT INTO conversations DEFAULT VALUES RETURNING id", Long.class);
            jdbc.update("INSERT INTO conversation_members (conversation_id, user_id) VALUES (?,?),(?,?)",
                convId, teacherId, convId, studentId);
        }

        jdbc.update(
            "INSERT INTO messages (conversation_id, sender_id, body, is_read, is_system, sent_at) VALUES (?,?,?,false,true,NOW())",
            convId, teacherId, crypto.encrypt(text));
    }
}
