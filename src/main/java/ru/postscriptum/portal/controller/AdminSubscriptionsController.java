package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/subscriptions")
@RequiredArgsConstructor
public class AdminSubscriptionsController {

    private final JdbcTemplate jdbc;
    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        List<Map<String, Object>> result = jdbc.query(
            "SELECT s.id, u.name AS student_name, " +
            "       COALESCE(lang.code,'') AS lang, COALESCE(lang.name_ru,'') AS lang_name, " +
            "       sp.name AS plan_name, sp.lesson_count, sp.price, " +
            "       s.lessons_used, s.lessons_total, " +
            "       s.start_date, s.end_date, s.status, s.created_at " +
            "FROM subscriptions s " +
            "JOIN users u ON u.id = s.student_id " +
            "JOIN subscription_plans sp ON sp.id = s.plan_id " +
            "LEFT JOIN languages lang ON lang.id = sp.language_id " +
            "ORDER BY s.created_at DESC",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",        rs.getLong("id"));
                row.put("student",   rs.getString("student_name"));
                row.put("lang",      rs.getString("lang"));
                row.put("langName",  rs.getString("lang_name"));
                row.put("plan",      rs.getString("plan_name") + " · " + rs.getInt("lesson_count") + " уроков");
                row.put("used",      rs.getInt("lessons_used"));
                row.put("total",     rs.getInt("lessons_total"));
                row.put("price",     "₽ " + String.format("%,.0f", rs.getDouble("price")).replace(',', ' '));

                java.sql.Date endDate = rs.getDate("end_date");
                String expiresStr = "—";
                int daysLeft = 0;
                if (endDate != null) {
                    LocalDate end = endDate.toLocalDate();
                    expiresStr = end.format(RU_DATE);
                    daysLeft = (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), end);
                    if (daysLeft < 0) daysLeft = 0;
                }
                row.put("expires",  expiresStr);
                row.put("daysLeft", daysLeft);
                row.put("status",   rs.getString("status"));
                return row;
            }
        );

        return ResponseEntity.ok(result);
    }
}
