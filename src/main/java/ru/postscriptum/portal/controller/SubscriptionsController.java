package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.repository.UserRepository;
import ru.postscriptum.portal.service.BillingService;

import java.util.*;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionsController {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final BillingService billing;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        if (auth == null) return ResponseEntity.ok(List.of());

        String email = auth.getName();
        Long studentId;
        try {
            studentId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }

        return ResponseEntity.ok(billing.subscriptionsOf(studentId));
    }

    /** Ученик может сам покупать абонементы с этого возраста; младше — покупает родитель. */
    static final int SELF_PURCHASE_AGE = 18;

    /**
     * Запрос на покупку абонемента: body {planId, studentId?}.
     * Ученик с 18 лет покупает себе сам, родитель — своему ребёнку (studentId обязателен).
     * Онлайн-оплаты в системе пока нет, поэтому запрос уходит администраторам, а абонемент
     * оформляется после оплаты с указанием покупателя (purchasedBy).
     */
    @PostMapping("/purchase-request")
    public ResponseEntity<?> purchaseRequest(Authentication auth, @RequestBody Map<String, Object> body) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> me = jdbc.queryForMap("SELECT id, name, role::text AS role FROM users WHERE email = ?", auth.getName());
        long meId = ((Number) me.get("id")).longValue();
        String role = (String) me.get("role");

        if (!(body.get("planId") instanceof Number planNum)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Не выбран тариф"));
        }
        long planId = planNum.longValue();

        long studentId;
        if ("STUDENT".equals(role)) {
            studentId = meId;
            java.sql.Date birth = null;
            try {
                birth = jdbc.queryForObject("SELECT birth_date FROM student_profiles WHERE user_id=?", java.sql.Date.class, meId);
            } catch (Exception ignored) { }
            if (birth == null) {
                return ResponseEntity.status(403).body(Map.of("message",
                    "Укажите дату рождения в профиле: самостоятельно покупать абонементы можно с " + SELF_PURCHASE_AGE + " лет"));
            }
            int age = java.time.Period.between(birth.toLocalDate(), java.time.LocalDate.now()).getYears();
            if (age < SELF_PURCHASE_AGE) {
                return ResponseEntity.status(403).body(Map.of("message",
                    "Ученикам младше " + SELF_PURCHASE_AGE + " лет абонемент оформляет родитель"));
            }
        } else if ("PARENT".equals(role)) {
            if (!(body.get("studentId") instanceof Number sNum)) {
                return ResponseEntity.badRequest().body(Map.of("message", "Не выбран ребёнок"));
            }
            studentId = sNum.longValue();
            Integer own = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_profiles WHERE user_id=? AND parent_id=?", Integer.class, studentId, meId);
            if (own == null || own == 0) {
                return ResponseEntity.status(403).body(Map.of("message", "Можно оформить абонемент только своему ребёнку"));
            }
        } else {
            return ResponseEntity.status(403).body(Map.of("message", "Абонементы приобретают ученики и родители"));
        }

        Map<String, Object> plan;
        try {
            plan = jdbc.queryForMap("SELECT name FROM subscription_plans WHERE id=? AND is_active=true", planId);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Тариф не найден"));
        }
        String studentName = jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, studentId);
        // способ оплаты, выбранный в окне оплаты (заглушка до подключения платёжного шлюза)
        String method = "sbp".equals(body.get("paymentMethod")) ? "СБП" : "карта";
        String text = "Ученик: " + studentName + ", тариф: " + plan.get("name") + ", покупатель: " + me.get("name")
            + " (id " + meId + "), способ оплаты: " + method;

        jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
            SELECT u.id, 'SYSTEM'::notification_type, 'Запрос на покупку абонемента', ?, '/admin/subscriptions', false, NOW()
            FROM users u WHERE u.role IN ('ADMIN','MANAGER') AND u.is_active = true
            """, text);

        return ResponseEntity.ok(Map.of("status", "REQUESTED", "studentId", studentId, "purchasedBy", meId));
    }

    @GetMapping("/plans")
    public ResponseEntity<?> plans() {
        String sql = """
            SELECT p.id, p.name, p.lesson_count, p.price, COALESCE(lang.code,'') AS lang, COALESCE(lang.name_ru,'') AS lang_name
            FROM subscription_plans p LEFT JOIN languages lang ON lang.id = p.language_id
            WHERE p.is_active = true ORDER BY p.price
            """;

        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",          row.get("id"));
            item.put("name",        row.get("name"));
            item.put("lessonCount", row.get("lesson_count"));
            item.put("price",       row.get("price"));
            item.put("lang",        row.get("lang"));
            item.put("langName",    row.get("lang_name"));
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }
}
