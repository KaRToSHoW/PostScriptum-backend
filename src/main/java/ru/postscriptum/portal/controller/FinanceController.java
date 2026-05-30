package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final JdbcTemplate jdbc;

    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private String formatMoney(long amount) {
        return "₽ " + String.format("%,d", amount).replace(',', ' ');
    }

    @GetMapping
    public ResponseEntity<?> summary(@RequestParam(defaultValue = "MONTH") String period,
                                     Authentication auth) {
        if (auth == null) return ResponseEntity.ok(Map.of());

        // KPI 1 – Выручка за текущий месяц
        long revenue = 0;
        try {
            Long r = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM payments " +
                "WHERE status='PAID' AND DATE_TRUNC('month', paid_at) = DATE_TRUNC('month', CURRENT_DATE)",
                Long.class);
            if (r != null) revenue = r;
        } catch (Exception ignored) {}

        // KPI 2 – Активных абонементов
        long activeSubscriptions = 0;
        try {
            Long r = jdbc.queryForObject(
                "SELECT COUNT(*) FROM subscriptions WHERE status='ACTIVE'", Long.class);
            if (r != null) activeSubscriptions = r;
        } catch (Exception ignored) {}

        // KPI 3 – Средний чек
        long avgCheck = 0;
        try {
            Long r = jdbc.queryForObject(
                "SELECT COALESCE(AVG(amount),0) FROM payments WHERE status='PAID'", Long.class);
            if (r != null) avgCheck = r;
        } catch (Exception ignored) {}

        // KPI 4 – Просрочки
        long overdue = 0;
        try {
            Long r = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE status='OVERDUE'", Long.class);
            if (r != null) overdue = r;
        } catch (Exception ignored) {}

        // KPI 5 – Возвраты
        long refunded = 0;
        try {
            Long r = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE status='REFUNDED'", Long.class);
            if (r != null) refunded = r;
        } catch (Exception ignored) {}

        List<Map<String, Object>> kpi = List.of(
            Map.of("l", "Выручка · месяц", "v", formatMoney(revenue), "d", "текущий месяц", "up", true),
            Map.of("l", "Активных абонементов", "v", String.valueOf(activeSubscriptions), "d", "сейчас", "up", true),
            Map.of("l", "Средний чек", "v", formatMoney(avgCheck), "d", "все платежи", "up", true),
            Map.of("l", "Просрочки", "v", String.valueOf(overdue), "d", "не оплачено", "up", false),
            Map.of("l", "Возвраты", "v", String.valueOf(refunded), "d", "возвращено", "up", true)
        );

        // Revenue — last 6 months
        List<Map<String, Object>> revenueList = new ArrayList<>();
        String[] monthLabels = {"ЯНВ","ФЕВ","МАР","АПР","МАЙ","ИЮН","ИЮЛ","АВГ","СЕН","ОКТ","НОЯ","ДЕК"};
        LocalDate today = LocalDate.now();
        for (int i = 5; i >= 0; i--) {
            LocalDate monthDate = today.minusMonths(i);
            int monthNum = monthDate.getMonthValue();
            int year = monthDate.getYear();
            String label = monthLabels[monthNum - 1];

            long monthTotal = 0;
            try {
                Long r = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount),0) FROM payments " +
                    "WHERE status='PAID' AND EXTRACT(MONTH FROM paid_at)=? AND EXTRACT(YEAR FROM paid_at)=?",
                    Long.class, monthNum, year);
                if (r != null) monthTotal = r;
            } catch (Exception ignored) {}

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("m", label);
            entry.put("v", List.of(monthTotal));
            if (i == 0) entry.put("current", true);
            revenueList.add(entry);
        }

        // Subscriptions by language
        long activeSubs = activeSubscriptions;
        List<String> langLabels = new ArrayList<>();
        List<Long> langCounts = new ArrayList<>();
        try {
            jdbc.query(
                "SELECT l.name_ru, COUNT(*) AS cnt " +
                "FROM subscriptions s " +
                "JOIN subscription_plans sp ON sp.id = s.plan_id " +
                "JOIN languages l ON l.id = sp.language_id " +
                "WHERE s.status='ACTIVE' " +
                "GROUP BY l.name_ru ORDER BY cnt DESC",
                rs -> {
                    langLabels.add(rs.getString("name_ru"));
                    langCounts.add(rs.getLong("cnt"));
                }
            );
        } catch (Exception ignored) {}

        Map<String, Object> subscriptions = Map.of(
            "active", activeSubs,
            "labels", langLabels,
            "counts", langCounts
        );

        return ResponseEntity.ok(Map.of(
            "period", period,
            "kpi", kpi,
            "revenue", revenueList,
            "subscriptions", subscriptions
        ));
    }

    @GetMapping("/payments")
    public ResponseEntity<?> payments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {

        if (auth == null) {
            return ResponseEntity.ok(Map.of(
                "content", List.of(), "page", page, "size", size,
                "totalElements", 0, "totalPages", 0));
        }

        int offset = page * size;

        List<Map<String, Object>> content = jdbc.query(
            "SELECT p.id, u.name AS student, COALESCE(sp.name,'—') AS subscription, " +
            "       p.amount, p.method, p.status, COALESCE(p.paid_at, p.created_at) AS date " +
            "FROM payments p JOIN users u ON u.id = p.student_id " +
            "LEFT JOIN subscriptions s ON s.id = p.subscription_id " +
            "LEFT JOIN subscription_plans sp ON sp.id = s.plan_id " +
            "ORDER BY COALESCE(p.paid_at, p.created_at) DESC " +
            "LIMIT ? OFFSET ?",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("student", rs.getString("student"));
                row.put("subscription", rs.getString("subscription"));
                row.put("amount", formatMoney(rs.getLong("amount")));

                String method = rs.getString("method");
                row.put("method", switch (method == null ? "" : method) {
                    case "CARD"     -> "Карта";
                    case "SBP"      -> "СБП";
                    case "CASH"     -> "Наличные";
                    case "TRANSFER" -> "Перевод";
                    default         -> method;
                });

                String status = rs.getString("status");
                row.put("status", status == null ? "" : status.toLowerCase());

                java.sql.Timestamp ts = rs.getTimestamp("date");
                row.put("date", ts != null
                    ? ts.toLocalDateTime().toLocalDate().format(RU_DATE)
                    : "—");

                return row;
            },
            size, offset
        );

        long total = 0;
        try {
            Long t = jdbc.queryForObject("SELECT COUNT(*) FROM payments", Long.class);
            if (t != null) total = t;
        } catch (Exception ignored) {}

        long totalPages = size > 0 ? (total + size - 1) / size : 0;

        return ResponseEntity.ok(Map.of(
            "content",       content,
            "page",          page,
            "size",          size,
            "totalElements", total,
            "totalPages",    totalPages
        ));
    }
}
