package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Списание занятий с абонементов.
 *
 * Каждое списание привязано к участию ученика в занятии ({@code lesson_students.subscription_id}),
 * поэтому повторное списание невозможно, а при исправлении отметки посещения занятие
 * возвращается ровно на тот абонемент, с которого было списано.
 * Абонемент выбирается по языку занятия, среди подходящих — с самым ранним сроком окончания.
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    private final JdbcTemplate jdbc;

    /** Списывать ли занятие с отсутствовавшего ученика (неявка без отмены засчитывается). */
    @Value("${app.billing.charge-absent:true}")
    private boolean chargeAbsent;

    /**
     * Приводит списания по занятию в соответствие с его статусом и посещаемостью:
     * DONE — списать тем, кому положено, и вернуть остальным; MISSED — вернуть всем.
     * Можно вызывать многократно. Возвращает число новых списаний.
     */
    @Transactional
    public int settleLesson(long lessonId) {
        Map<String, Object> lesson;
        try {
            lesson = jdbc.queryForMap("SELECT status::text AS status, language_id FROM lessons WHERE id=?", lessonId);
        } catch (Exception e) {
            return 0;
        }
        String status = (String) lesson.get("status");
        Integer languageId = lesson.get("language_id") != null ? ((Number) lesson.get("language_id")).intValue() : null;

        int charged = 0;
        for (Map<String, Object> row : lockParticipants(lessonId)) {
            long studentId = ((Number) row.get("student_id")).longValue();
            Long subscriptionId = row.get("subscription_id") != null ? ((Number) row.get("subscription_id")).longValue() : null;
            boolean attended = Boolean.TRUE.equals(row.get("attended"));

            boolean shouldCharge = "DONE".equals(status) && (chargeAbsent || attended);
            if (shouldCharge && subscriptionId == null) {
                if (charge(lessonId, studentId, languageId)) charged++;
            } else if (!shouldCharge && subscriptionId != null && !"CANCELLED".equals(status)) {
                refund(lessonId, studentId, subscriptionId);
            }
        }
        return charged;
    }

    /** Поздняя отмена: занятие засчитывается всем ученикам занятия. Возвращает число списаний. */
    @Transactional
    public int chargeAll(long lessonId) {
        Integer languageId = jdbc.queryForObject("SELECT language_id FROM lessons WHERE id=?", Integer.class, lessonId);
        int charged = 0;
        for (Map<String, Object> row : lockParticipants(lessonId)) {
            if (row.get("subscription_id") != null) continue;
            if (charge(lessonId, ((Number) row.get("student_id")).longValue(), languageId)) charged++;
        }
        return charged;
    }

    private List<Map<String, Object>> lockParticipants(long lessonId) {
        return jdbc.queryForList(
            "SELECT student_id, subscription_id, attended FROM lesson_students WHERE lesson_id=? FOR UPDATE",
            lessonId);
    }

    /** Списывает одно занятие; false — если подходящего действующего абонемента нет. */
    private boolean charge(long lessonId, long studentId, Integer languageId) {
        List<Long> ids = jdbc.queryForList("""
            UPDATE subscriptions SET lessons_used = lessons_used + 1
            WHERE id = (
                SELECT s.id FROM subscriptions s
                JOIN subscription_plans p ON p.id = s.plan_id
                WHERE s.student_id = ? AND s.status = 'ACTIVE' AND s.lessons_used < s.lessons_total
                  AND (p.language_id IS NULL OR ?::int IS NULL OR p.language_id = ?::int)
                ORDER BY (p.language_id IS NULL), s.end_date ASC NULLS LAST, s.id
                LIMIT 1
                FOR UPDATE OF s
            )
            RETURNING id
            """, Long.class, studentId, languageId, languageId);
        if (ids.isEmpty()) return false;
        jdbc.update("UPDATE lesson_students SET subscription_id=? WHERE lesson_id=? AND student_id=?",
            ids.get(0), lessonId, studentId);
        return true;
    }

    private void refund(long lessonId, long studentId, long subscriptionId) {
        jdbc.update("UPDATE subscriptions SET lessons_used = GREATEST(lessons_used - 1, 0) WHERE id=?", subscriptionId);
        jdbc.update("UPDATE lesson_students SET subscription_id=NULL WHERE lesson_id=? AND student_id=?",
            lessonId, studentId);
    }

    /** Абонементы ученика для показа (ученику — свои, родителю — абонементы ребёнка). */
    public List<Map<String, Object>> subscriptionsOf(long studentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT s.id, s.lessons_used, s.lessons_total, s.start_date, s.end_date, s.status,
                   p.name AS plan_name, p.price, COALESCE(lang.code, '') AS lang, COALESCE(lang.name_ru, '') AS lang_name,
                   buyer.name AS purchased_by_name
            FROM subscriptions s
            JOIN subscription_plans p ON p.id = s.plan_id
            LEFT JOIN languages lang ON lang.id = p.language_id
            LEFT JOIN users buyer ON buyer.id = s.purchased_by
            WHERE s.student_id = ?
            ORDER BY s.created_at DESC
            """, studentId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",          row.get("id"));
            item.put("planName",    row.get("plan_name"));
            item.put("lang",        row.get("lang"));
            item.put("langName",    row.get("lang_name"));
            item.put("used",        row.get("lessons_used"));
            item.put("total",       row.get("lessons_total"));
            item.put("startDate",   row.get("start_date"));
            item.put("endDate",     row.get("end_date"));
            item.put("status",      row.get("status"));
            item.put("price",       row.get("price"));
            item.put("purchasedBy", row.get("purchased_by_name"));
            result.add(item);
        }
        return result;
    }
}
