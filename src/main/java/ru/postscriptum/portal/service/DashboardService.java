package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.postscriptum.portal.repository.UserRepository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;

    // ─── helpers ────────────────────────────────────────────────────────────

    private static final ZoneOffset MSK = ZoneOffset.ofHours(3);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private OffsetDateTime toMsk(Object raw) {
        if (raw instanceof Timestamp ts) {
            return ts.toInstant().atOffset(MSK);
        }
        return null;
    }

    private String dayLabel(OffsetDateTime dt) {
        if (dt == null) return "";
        return switch (dt.getDayOfWeek().getValue()) {
            case 1 -> "ПН";
            case 2 -> "ВТ";
            case 3 -> "СР";
            case 4 -> "ЧТ";
            case 5 -> "ПТ";
            case 6 -> "СБ";
            case 7 -> "ВС";
            default -> "";
        };
    }

    private String hwStatus(Object raw) {
        if (raw == null) return "not_started";
        return switch (raw.toString()) {
            case "ASSIGNED"  -> "not_started";
            case "SUBMITTED" -> "submitted";
            case "REVIEWED"  -> "done";
            case "OVERDUE"   -> "overdue";
            default          -> raw.toString().toLowerCase();
        };
    }

    // ─── student dashboard ──────────────────────────────────────────────────

    public Map<String, Object> getStudentDashboard(Long studentId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // streak
        int streak = 0;
        try {
            Integer s = jdbc.queryForObject(
                    "SELECT streak_days FROM student_profiles WHERE user_id = ?",
                    Integer.class, studentId);
            if (s != null) streak = s;
        } catch (EmptyResultDataAccessException ignored) {}
        result.put("streak", streak);

        // subscription
        Map<String, Object> subscription = new LinkedHashMap<>();
        subscription.put("used", 0);
        subscription.put("total", 0);
        subscription.put("expiresAt", null);
        try {
            List<Map<String, Object>> subs = jdbc.queryForList(
                    "SELECT lessons_used, lessons_total, end_date " +
                    "FROM subscriptions " +
                    "WHERE student_id = ? AND status = 'ACTIVE' " +
                    "ORDER BY end_date DESC LIMIT 1",
                    studentId);
            if (!subs.isEmpty()) {
                Map<String, Object> row = subs.get(0);
                subscription.put("used",      row.get("lessons_used"));
                subscription.put("total",     row.get("lessons_total"));
                Object endDate = row.get("end_date");
                subscription.put("expiresAt", endDate != null ? endDate.toString() : null);
            }
        } catch (EmptyResultDataAccessException ignored) {}
        result.put("subscription", subscription);

        // courses
        List<Map<String, Object>> courseRows = jdbc.queryForList(
                "SELECT e.id, l.name_ru AS language, l.code AS lang, " +
                "       u.name AS teacher, e.progress_pct AS progress " +
                "FROM enrollments e " +
                "JOIN courses c ON c.id = e.course_id " +
                "JOIN languages l ON l.id = c.language_id " +
                "JOIN users u ON u.id = e.teacher_id " +
                "WHERE e.student_id = ? AND e.is_active = true",
                studentId);

        List<Map<String, Object>> courses = new ArrayList<>();
        for (Map<String, Object> row : courseRows) {
            Map<String, Object> course = new LinkedHashMap<>();
            course.put("id",       row.get("id"));
            course.put("language", row.get("language"));
            course.put("lang",     row.get("lang"));
            course.put("teacher",  row.get("teacher"));
            course.put("nextDate", null);  // populated below if schedule found
            course.put("progress", row.get("progress"));
            courses.add(course);
        }
        result.put("courses", courses);

        // schedule (upcoming planned lessons for student)
        List<Map<String, Object>> scheduleRows = jdbc.queryForList(
                "SELECT ls2.scheduled_at, ls2.duration_min, ls2.zoom_url, " +
                "       u.name AS teacher, lang.name_ru AS language, lang.code AS lang " +
                "FROM lesson_students ls " +
                "JOIN lessons ls2 ON ls2.id = ls.lesson_id " +
                "JOIN users u ON u.id = ls2.teacher_id " +
                "JOIN languages lang ON lang.id = ls2.language_id " +
                "WHERE ls.student_id = ? " +
                "  AND ls2.status = 'PLANNED' " +
                "  AND ls2.scheduled_at > NOW() " +
                "ORDER BY ls2.scheduled_at ASC " +
                "LIMIT 5",
                studentId);

        List<Map<String, Object>> schedule = new ArrayList<>();
        for (Map<String, Object> row : scheduleRows) {
            OffsetDateTime dt = toMsk(row.get("scheduled_at"));
            if (dt == null) continue;

            int dur = row.get("duration_min") != null
                    ? ((Number) row.get("duration_min")).intValue() : 60;
            OffsetDateTime dtEnd = dt.plusMinutes(dur);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date",     String.valueOf(dt.getDayOfMonth()));
            item.put("dayLabel", dayLabel(dt));
            item.put("timeFrom", dt.format(HH_MM));
            item.put("timeTo",   dtEnd.format(HH_MM));
            item.put("teacher",  row.get("teacher"));
            item.put("lang",     row.get("lang"));
            item.put("who",      row.get("language"));
            schedule.add(item);
        }
        result.put("schedule", schedule);

        // nextLesson — first entry from schedule
        if (!schedule.isEmpty()) {
            Map<String, Object> first = schedule.get(0);
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("date",     first.get("date"));
            next.put("dayLabel", first.get("dayLabel"));
            next.put("time",     first.get("timeFrom"));
            next.put("teacher",  first.get("teacher"));
            next.put("lang",     first.get("lang"));
            // zoom_url lives in the raw row
            next.put("zoomUrl",  scheduleRows.get(0).get("zoom_url"));
            result.put("nextLesson", next);
        } else {
            result.put("nextLesson", null);
        }

        // homework
        List<Map<String, Object>> hwRows = jdbc.queryForList(
                "SELECT h.id, h.title, h.due_at, h.status, " +
                "       lang.code AS lang " +
                "FROM homework h " +
                "LEFT JOIN lessons l ON l.id = h.lesson_id " +
                "LEFT JOIN languages lang ON lang.id = l.language_id " +
                "WHERE h.student_id = ? " +
                "ORDER BY h.due_at ASC " +
                "LIMIT 5",
                studentId);

        List<Map<String, Object>> homework = new ArrayList<>();
        for (Map<String, Object> row : hwRows) {
            OffsetDateTime due = toMsk(row.get("due_at"));
            Map<String, Object> hw = new LinkedHashMap<>();
            hw.put("id",     row.get("id"));
            hw.put("title",  row.get("title"));
            hw.put("due",    due != null ? due.format(HH_MM) : null);
            hw.put("status", hwStatus(row.get("status")));
            hw.put("lang",   row.get("lang"));
            homework.add(hw);
        }
        result.put("homework", homework);

        return result;
    }

    // ─── teacher dashboard ──────────────────────────────────────────────────

    public Map<String, Object> getTeacherDashboard(Long teacherId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // schedule — upcoming planned lessons for this teacher
        List<Map<String, Object>> scheduleRows = jdbc.queryForList(
                "SELECT l.scheduled_at, l.duration_min, l.status, " +
                "       u.name AS student, lang.code AS lang " +
                "FROM lessons l " +
                "JOIN lesson_students ls ON ls.lesson_id = l.id " +
                "JOIN users u ON u.id = ls.student_id " +
                "JOIN languages lang ON lang.id = l.language_id " +
                "WHERE l.teacher_id = ? " +
                "  AND l.status = 'PLANNED' " +
                "  AND l.scheduled_at > NOW() " +
                "ORDER BY l.scheduled_at ASC " +
                "LIMIT 20",
                teacherId);

        List<Map<String, Object>> schedule = new ArrayList<>();
        for (Map<String, Object> row : scheduleRows) {
            OffsetDateTime dt = toMsk(row.get("scheduled_at"));
            if (dt == null) continue;

            int dur = row.get("duration_min") != null
                    ? ((Number) row.get("duration_min")).intValue() : 60;
            OffsetDateTime dtEnd = dt.plusMinutes(dur);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date",     String.valueOf(dt.getDayOfMonth()));
            item.put("dayLabel", dayLabel(dt));
            item.put("timeFrom", dt.format(HH_MM));
            item.put("timeTo",   dtEnd.format(HH_MM));
            item.put("student",  row.get("student"));
            item.put("lang",     row.get("lang"));
            item.put("status",   row.get("status"));
            schedule.add(item);
        }
        result.put("schedule", schedule);

        // workload — lessons count per day of current week (Mon–Sun)
        LocalDate today = LocalDate.now(MSK);
        LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);

        String[] dayLabels = {"ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС"};

        // Count lessons per calendar day of this week
        List<Map<String, Object>> lessonDayCounts = jdbc.queryForList(
                "SELECT DATE(scheduled_at AT TIME ZONE 'Europe/Moscow') AS lesson_date, " +
                "       SUM(duration_min) AS total_min " +
                "FROM lessons " +
                "WHERE teacher_id = ? " +
                "  AND scheduled_at >= ? " +
                "  AND scheduled_at < ? " +
                "GROUP BY lesson_date",
                teacherId,
                java.sql.Date.valueOf(monday),
                java.sql.Date.valueOf(monday.plusWeeks(1)));

        // Build a lookup: date -> total_min
        Map<LocalDate, Integer> minutesByDay = new HashMap<>();
        for (Map<String, Object> row : lessonDayCounts) {
            Object dateVal = row.get("lesson_date");
            Object minVal  = row.get("total_min");
            if (dateVal == null) continue;
            LocalDate date;
            if (dateVal instanceof java.sql.Date sd) {
                date = sd.toLocalDate();
            } else {
                date = LocalDate.parse(dateVal.toString());
            }
            int mins = minVal != null ? ((Number) minVal).intValue() : 0;
            minutesByDay.put(date, mins);
        }

        int capacityMinPerDay = 8 * 60; // 8 hours / day max
        int totalMinutes = 0;

        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            int mins = minutesByDay.getOrDefault(day, 0);
            totalMinutes += mins;
            int pct = (int) Math.min(100, Math.round((mins * 100.0) / capacityMinPerDay));

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("label", dayLabels[i]);
            d.put("pct",   pct);
            d.put("today", day.equals(today));
            days.add(d);
        }

        int totalHours    = totalMinutes / 60;
        int capacityHours = 7 * 8; // 56h/week

        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("days",       days);
        workload.put("totalHours", totalHours);
        workload.put("capacity",   capacityHours);
        result.put("workload", workload);

        // attention — placeholder
        result.put("attention", List.of());

        return result;
    }
}
