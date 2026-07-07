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

        // streak — количество проведённых занятий подряд без пропуска
        int streak = 0;
        try {
            List<String> statuses = jdbc.queryForList(
                    "SELECT l.status::text FROM lessons l " +
                    "JOIN lesson_students ls ON ls.lesson_id = l.id " +
                    "WHERE ls.student_id = ? AND l.status IN ('DONE','MISSED') " +
                    "ORDER BY l.scheduled_at DESC LIMIT 200",
                    String.class, studentId);
            for (String st : statuses) {
                if ("DONE".equals(st)) streak++;
                else break;
            }
        } catch (Exception ignored) {}
        result.put("streak", streak);

        // пропуски — всего пропущенных уроков
        int missed = 0;
        try {
            Integer m = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM lessons l " +
                    "JOIN lesson_students ls ON ls.lesson_id = l.id " +
                    "WHERE ls.student_id = ? AND l.status = 'MISSED'",
                    Integer.class, studentId);
            if (m != null) missed = m;
        } catch (Exception ignored) {}
        result.put("missed", missed);

        // subscription — суммируем все активные абонементы
        Map<String, Object> subscription = new LinkedHashMap<>();
        subscription.put("used", 0);
        subscription.put("total", 0);
        subscription.put("expiresAt", null);
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT COALESCE(SUM(lessons_used), 0)  AS used, " +
                    "       COALESCE(SUM(lessons_total), 0) AS total, " +
                    "       MAX(end_date)                   AS end_date " +
                    "FROM subscriptions " +
                    "WHERE student_id = ? AND status = 'ACTIVE'",
                    studentId);
            subscription.put("used",  ((Number) row.get("used")).intValue());
            subscription.put("total", ((Number) row.get("total")).intValue());
            Object endDate = row.get("end_date");
            subscription.put("expiresAt", endDate != null ? endDate.toString() : null);
        } catch (EmptyResultDataAccessException ignored) {}
        result.put("subscription", subscription);

        // courses (enrollments → languages, no courses table)
        List<Map<String, Object>> courseRows = jdbc.queryForList(
                "SELECT e.id, e.language_id, l.name_ru AS language, l.code AS lang, " +
                "       u.name AS teacher " +
                "FROM enrollments e " +
                "JOIN languages l ON l.id = e.language_id " +
                "JOIN users u ON u.id = e.teacher_id " +
                "WHERE e.student_id = ? AND e.is_active = true",
                studentId);

        // ближайший запланированный урок по каждому языку ученика
        Map<Long, OffsetDateTime> nextByLang = new HashMap<>();
        try {
            List<Map<String, Object>> nextRows = jdbc.queryForList(
                    "SELECT l.language_id, MIN(l.scheduled_at) AS next_at " +
                    "FROM lessons l " +
                    "JOIN lesson_students ls ON ls.lesson_id = l.id " +
                    "WHERE ls.student_id = ? AND l.status = 'PLANNED' AND l.scheduled_at > NOW() " +
                    "GROUP BY l.language_id",
                    studentId);
            for (Map<String, Object> row : nextRows) {
                OffsetDateTime dt = toMsk(row.get("next_at"));
                if (dt != null) nextByLang.put(((Number) row.get("language_id")).longValue(), dt);
            }
        } catch (Exception ignored) {}

        String[] MONTH_GEN = {"янв","фев","мар","апр","мая","июн","июл","авг","сен","окт","ноя","дек"};

        List<Map<String, Object>> courses = new ArrayList<>();
        for (Map<String, Object> row : courseRows) {
            Map<String, Object> course = new LinkedHashMap<>();
            course.put("id",       row.get("id"));
            course.put("language", row.get("language"));
            course.put("lang",     row.get("lang"));
            course.put("teacher",  row.get("teacher"));
            OffsetDateTime next = nextByLang.get(((Number) row.get("language_id")).longValue());
            course.put("nextDate", next != null
                    ? next.getDayOfMonth() + " " + MONTH_GEN[next.getMonthValue() - 1] + ", " + next.format(HH_MM)
                    : null);
            courses.add(course);
        }
        result.put("courses", courses);

        // schedule (upcoming planned lessons for student)
        List<Map<String, Object>> scheduleRows = jdbc.queryForList(
                "SELECT ls2.id, ls2.scheduled_at, ls2.duration_min, ls2.zoom_url, " +
                "       u.name AS teacher, lang.name_ru AS language, lang.code AS lang " +
                "FROM lesson_students ls " +
                "JOIN lessons ls2 ON ls2.id = ls.lesson_id " +
                "JOIN users u ON u.id = ls2.teacher_id " +
                "JOIN languages lang ON lang.id = ls2.language_id " +
                "WHERE ls.student_id = ? " +
                "  AND ls2.status IN ('PLANNED','IN_PROGRESS') " +
                "  AND (ls2.scheduled_at + (COALESCE(ls2.duration_min, 60) || ' minutes')::interval) > NOW() " +
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
            next.put("id",       scheduleRows.get(0).get("id"));
            Object rawAt = scheduleRows.get(0).get("scheduled_at");
            next.put("startAt",  rawAt instanceof Timestamp ts ? ts.toInstant().toEpochMilli() : null);
            next.put("durMin",   scheduleRows.get(0).get("duration_min") != null
                    ? ((Number) scheduleRows.get(0).get("duration_min")).intValue() : 60);
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
                "       COALESCE(llang.code, hlang.code) AS lang " +
                "FROM homework h " +
                "LEFT JOIN lessons l ON l.id = h.lesson_id " +
                "LEFT JOIN languages llang ON llang.id = l.language_id " +
                "LEFT JOIN languages hlang ON hlang.id = h.language_id " +
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

        // schedule — upcoming planned lessons for this teacher (next 7 days)
        List<Map<String, Object>> scheduleRows = jdbc.queryForList(
                "SELECT l.id, l.scheduled_at, l.duration_min, l.status, " +
                "       u.name AS student, u.initials AS student_initials, lang.code AS lang " +
                "FROM lessons l " +
                "JOIN lesson_students ls ON ls.lesson_id = l.id " +
                "JOIN users u ON u.id = ls.student_id " +
                "JOIN languages lang ON lang.id = l.language_id " +
                "WHERE l.teacher_id = ? " +
                "  AND l.status IN ('PLANNED','IN_PROGRESS') " +
                "  AND (l.scheduled_at + (COALESCE(l.duration_min, 60) || ' minutes')::interval) > NOW() " +
                "  AND l.scheduled_at < NOW() + INTERVAL '7 days' " +
                "ORDER BY l.scheduled_at ASC",
                teacherId);

        DateTimeFormatter DATE_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String[] MONTH_SHORT = {"янв","фев","мар","апр","май","июн","июл","авг","сен","окт","ноя","дек"};

        List<Map<String, Object>> schedule = new ArrayList<>();
        for (Map<String, Object> row : scheduleRows) {
            OffsetDateTime dt = toMsk(row.get("scheduled_at"));
            if (dt == null) continue;

            int dur = row.get("duration_min") != null
                    ? ((Number) row.get("duration_min")).intValue() : 60;
            OffsetDateTime dtEnd = dt.plusMinutes(dur);

            String monthShort = MONTH_SHORT[dt.getMonthValue() - 1];

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",       row.get("id"));
            Object rawTs = row.get("scheduled_at");
            item.put("startAt",  rawTs instanceof Timestamp ts ? ts.toInstant().toEpochMilli() : null);
            item.put("durMin",   dur);
            item.put("dateKey",  dt.format(DATE_KEY));
            item.put("date",     String.valueOf(dt.getDayOfMonth()));
            item.put("month",    monthShort);
            item.put("dayLabel", dayLabel(dt));
            item.put("timeFrom", dt.format(HH_MM));
            item.put("timeTo",   dtEnd.format(HH_MM));
            item.put("student",  row.get("student"));
            item.put("studentInitials", row.get("student_initials"));
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

        // attention — реальные события, требующие внимания преподавателя
        List<Map<String, Object>> attention = new ArrayList<>();

        // 1) Сданные домашки, ожидающие проверки
        try {
            List<Map<String, Object>> submitted = jdbc.queryForList(
                    "SELECT s.name AS who, h.title, hs.submitted_at " +
                    "FROM homework h " +
                    "JOIN homework_submissions hs ON hs.homework_id = h.id " +
                    "JOIN users s ON s.id = h.student_id " +
                    "WHERE h.teacher_id = ? AND h.status = 'SUBMITTED' " +
                    "ORDER BY hs.submitted_at DESC LIMIT 5",
                    teacherId);
            for (Map<String, Object> row : submitted) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("who",  row.get("who"));
                a.put("what", "Прислал(а) работу: " + row.get("title"));
                a.put("timeAgo", relTime(toMsk(row.get("submitted_at"))));
                a.put("type", "orange");
                attention.add(a);
            }
        } catch (Exception ignored) {}

        // 2) Пропущенные уроки
        try {
            List<Map<String, Object>> missed = jdbc.queryForList(
                    "SELECT u.name AS who, l.scheduled_at " +
                    "FROM lessons l " +
                    "JOIN lesson_students ls ON ls.lesson_id = l.id " +
                    "JOIN users u ON u.id = ls.student_id " +
                    "WHERE l.teacher_id = ? AND l.status = 'MISSED' " +
                    "ORDER BY l.scheduled_at DESC LIMIT 3",
                    teacherId);
            for (Map<String, Object> row : missed) {
                OffsetDateTime dt = toMsk(row.get("scheduled_at"));
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("who",  row.get("who"));
                a.put("what", "Пропустил(а) урок" + (dt != null ? " " + dt.getDayOfMonth() + "." + String.format("%02d", dt.getMonthValue()) : ""));
                a.put("timeAgo", relTime(dt));
                a.put("type", "red");
                attention.add(a);
            }
        } catch (Exception ignored) {}

        result.put("attention", attention);

        return result;
    }

    private String relTime(OffsetDateTime dt) {
        if (dt == null) return "";
        long mins = java.time.Duration.between(dt, OffsetDateTime.now(MSK)).toMinutes();
        if (mins < 0)   return "скоро";
        if (mins < 60)  return mins + " мин назад";
        long h = mins / 60;
        if (h < 24)     return h + " ч назад";
        long d = h / 24;
        if (d == 1)     return "вчера";
        return d + " дн назад";
    }
}
