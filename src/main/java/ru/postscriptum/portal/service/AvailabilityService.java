package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Окна доступности преподавателя: день недели (1 = ПН … 7 = ВС) и интервал времени
 * в часовом поясе преподавателя. Если окна не заданы, ограничений нет.
 */
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final JdbcTemplate jdbc;

    public List<Map<String, Object>> list(long teacherId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, weekday, start_time, end_time FROM teacher_availability
            WHERE teacher_id = ? ORDER BY weekday, start_time
            """, teacherId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",      r.get("id"));
            item.put("weekday", r.get("weekday"));
            item.put("start",   r.get("start_time").toString().substring(0, 5));
            item.put("end",     r.get("end_time").toString().substring(0, 5));
            result.add(item);
        }
        return result;
    }

    /** Полностью заменяет окна преподавателя. Бросает IllegalArgumentException при ошибке в данных. */
    @Transactional
    public void replace(long teacherId, List<?> windows) {
        List<Object[]> rows = new ArrayList<>();
        for (Object o : windows) {
            if (!(o instanceof Map<?, ?> w)) throw new IllegalArgumentException("Некорректное окно доступности");
            int weekday = ((Number) w.get("weekday")).intValue();
            LocalTime start = LocalTime.parse(String.valueOf(w.get("start")));
            LocalTime end   = LocalTime.parse(String.valueOf(w.get("end")));
            if (weekday < 1 || weekday > 7) throw new IllegalArgumentException("День недели должен быть от 1 до 7");
            if (!end.isAfter(start)) throw new IllegalArgumentException("Время окончания должно быть позже начала");
            rows.add(new Object[]{teacherId, weekday, Time.valueOf(start), Time.valueOf(end)});
        }
        jdbc.update("DELETE FROM teacher_availability WHERE teacher_id = ?", teacherId);
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO teacher_availability (teacher_id, weekday, start_time, end_time) VALUES (?,?,?,?)", rows);
        }
    }

    /**
     * true — если у преподавателя заданы окна и занятие [start, start + duration) не помещается ни в одно из них.
     * Сравнение выполняется в часовом поясе преподавателя.
     */
    public boolean isOutsideWindows(long teacherId, ZonedDateTime start, int durationMin) {
        List<Map<String, Object>> windows = jdbc.queryForList(
            "SELECT weekday, start_time, end_time FROM teacher_availability WHERE teacher_id = ?", teacherId);
        if (windows.isEmpty()) return false;

        String tz = jdbc.queryForObject("SELECT timezone FROM users WHERE id = ?", String.class, teacherId);
        ZoneId zone;
        try {
            zone = ZoneId.of(tz != null && !tz.isBlank() ? tz : "Europe/Moscow");
        } catch (Exception e) {
            zone = ZoneId.of("Europe/Moscow");
        }
        ZonedDateTime local = start.withZoneSameInstant(zone);
        ZonedDateTime localEnd = local.plusMinutes(durationMin);
        if (!localEnd.toLocalDate().equals(local.toLocalDate()) && !localEnd.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return true; // занятие переходит через полночь — в одно окно не помещается
        }
        int weekday = local.getDayOfWeek().getValue();
        LocalTime from = local.toLocalTime();
        LocalTime to = localEnd.toLocalTime().equals(LocalTime.MIDNIGHT) ? LocalTime.MAX : localEnd.toLocalTime();

        for (Map<String, Object> w : windows) {
            if (((Number) w.get("weekday")).intValue() != weekday) continue;
            LocalTime ws = ((Time) w.get("start_time")).toLocalTime();
            LocalTime we = ((Time) w.get("end_time")).toLocalTime();
            if (!from.isBefore(ws) && !to.isAfter(we)) return false;
        }
        return true;
    }

    public String outsideWarning(ZonedDateTime start) {
        return "Время " + start.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
            + " вне окон доступности преподавателя";
    }
}
