package ru.postscriptum.portal.controller;

import livekit.LivekitWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.service.AttendanceService;
import ru.postscriptum.portal.service.BillingService;
import ru.postscriptum.portal.service.LiveKitService;

import java.time.Instant;
import java.util.*;

/**
 * Видеоконференции занятий на медиасервере LiveKit.
 *
 * Серверная часть проверяет, что пользователь — участник занятия, и выдаёт токен доступа
 * к комнате {@code lesson-<id>}. Медиасервер присылает веб-хуки о входе и выходе участников,
 * по ним накапливается время присутствия учеников. Завершение занятия преподавателем
 * закрывает комнату, ставит отметки посещения по порогу и списывает занятия с абонементов.
 */
@Slf4j
@RestController
@RequestMapping("/api/conference")
@RequiredArgsConstructor
public class ConferenceController {

    /** За сколько секунд до начала открывается комната. */
    private static final long OPEN_BEFORE_SECONDS = 10 * 60;

    private final JdbcTemplate jdbc;
    private final LiveKitService liveKit;
    private final AttendanceService attendance;
    private final BillingService billing;

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private Map<String, Object> me(Authentication auth) {
        return jdbc.queryForMap("SELECT id, name, initials, role FROM users WHERE email = ?", auth.getName());
    }

    /** Участник занятия: его преподаватель, ученик этого занятия, менеджер или администратор. Родитель — нет. */
    private boolean isParticipant(long lessonId, long userId, String role) {
        Integer cnt;
        if ("TEACHER".equals(role)) {
            cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lessons WHERE id=? AND teacher_id=?", Integer.class, lessonId, userId);
        } else if ("ADMIN".equals(role) || "MANAGER".equals(role)) {
            cnt = jdbc.queryForObject("SELECT COUNT(*) FROM lessons WHERE id=?", Integer.class, lessonId);
        } else if ("STUDENT".equals(role)) {
            cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lesson_students WHERE lesson_id=? AND student_id=?", Integer.class, lessonId, userId);
        } else {
            cnt = 0;
        }
        return cnt != null && cnt > 0;
    }

    /* ── список уроков для вкладки «Конференции» ─────────────────────────── */

    @GetMapping("/lessons")
    public ResponseEntity<?> lessons(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> u = me(auth);
        long userId = ((Number) u.get("id")).longValue();
        String role = (String) u.get("role");

        List<Map<String, Object>> rows;
        if ("TEACHER".equals(role)) {
            rows = jdbc.queryForList("""
                SELECT l.id, l.scheduled_at, l.duration_min, l.status::text AS status,
                       lang.name_ru AS language, lang.code AS lang,
                       STRING_AGG(DISTINCT s.name, ', ' ORDER BY s.name) AS with_whom
                FROM lessons l
                JOIN languages lang ON lang.id = l.language_id
                LEFT JOIN lesson_students ls ON ls.lesson_id = l.id
                LEFT JOIN users s ON s.id = ls.student_id
                WHERE l.teacher_id = ? AND l.status IN ('PLANNED','IN_PROGRESS')
                  AND l.scheduled_at > NOW() - INTERVAL '3 hours'
                  AND l.scheduled_at < NOW() + INTERVAL '7 days'
                GROUP BY l.id, l.scheduled_at, l.duration_min, l.status, lang.name_ru, lang.code
                ORDER BY l.scheduled_at ASC
                """, userId);
        } else {
            rows = jdbc.queryForList("""
                SELECT l.id, l.scheduled_at, l.duration_min, l.status::text AS status,
                       lang.name_ru AS language, lang.code AS lang,
                       t.name AS with_whom
                FROM lessons l
                JOIN lesson_students ls ON ls.lesson_id = l.id
                JOIN languages lang ON lang.id = l.language_id
                JOIN users t ON t.id = l.teacher_id
                WHERE ls.student_id = ? AND l.status IN ('PLANNED','IN_PROGRESS')
                  AND l.scheduled_at > NOW() - INTERVAL '3 hours'
                  AND l.scheduled_at < NOW() + INTERVAL '7 days'
                ORDER BY l.scheduled_at ASC
                """, userId);
        }
        return ResponseEntity.ok(rows);
    }

    /* ── информация об уроке: участники + домашние задания ──────────────────────── */

    @GetMapping("/{lessonId}/info")
    public ResponseEntity<?> info(@PathVariable long lessonId, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> u = me(auth);
        long userId = ((Number) u.get("id")).longValue();
        String role = (String) u.get("role");
        if (!isParticipant(lessonId, userId, role)) {
            return ResponseEntity.status(403).body(Map.of("message", "Вы не участник этого урока"));
        }

        Map<String, Object> lesson;
        try {
            lesson = jdbc.queryForMap("""
                SELECT l.id, l.scheduled_at, l.duration_min, l.status::text AS status,
                       lang.name_ru AS language, lang.code AS lang,
                       t.id AS teacher_id, t.name AS teacher, t.initials AS teacher_initials,
                       t.avatar_url AS teacher_avatar
                FROM lessons l
                JOIN languages lang ON lang.id = l.language_id
                JOIN users t ON t.id = l.teacher_id
                WHERE l.id = ?
                """, lessonId);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("message", "Урок не найден"));
        }

        List<Map<String, Object>> students = jdbc.queryForList("""
            SELECT u.id, u.name, u.initials, u.avatar_url AS "avatarUrl", ls.attended,
                   ls.present_seconds AS "presentSeconds"
            FROM lesson_students ls JOIN users u ON u.id = ls.student_id
            WHERE ls.lesson_id = ? ORDER BY u.name
            """, lessonId);

        // домашние задания к занятию; материалы урока — отдельно, /api/lessons/{id}/materials
        // ученику — статус его решения, преподавателю — сколько учеников уже сдали
        List<Map<String, Object>> homework = jdbc.queryForList("""
            SELECT h.id, h.title, h.description, h.attachment_url, h.due_at,
                   mine.status AS my_status, mine.grade AS my_grade,
                   (SELECT COUNT(*) FROM homework_submissions s WHERE s.homework_id = h.id) AS submitted
            FROM homework h
            LEFT JOIN homework_submissions mine ON mine.homework_id = h.id AND mine.student_id = ?
            WHERE h.lesson_id = ?
            ORDER BY h.created_at ASC
            """, userId, lessonId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lesson",    lesson);
        result.put("students",  students);
        result.put("homework",  homework);
        result.put("myId",      userId);
        result.put("myRole",    role);
        result.put("myName",    u.get("name"));
        return ResponseEntity.ok(result);
    }

    /* ── вход в комнату: проверка прав и выдача токена LiveKit ───────────── */

    @PostMapping("/{lessonId}/join")
    public ResponseEntity<?> join(@PathVariable long lessonId, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> u = me(auth);
        long userId = ((Number) u.get("id")).longValue();
        String role = (String) u.get("role");
        if (!isParticipant(lessonId, userId, role)) {
            return ResponseEntity.status(403).body(Map.of("message", "Вы не участник этого урока"));
        }
        if (!liveKit.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of("message", "Видеосвязь временно недоступна: медиасервер не настроен"));
        }

        Map<String, Object> l;
        try {
            l = jdbc.queryForMap("SELECT scheduled_at, status::text AS status FROM lessons WHERE id=?", lessonId);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("message", "Урок не найден"));
        }
        String status = (String) l.get("status");
        if ("DONE".equals(status) || "CANCELLED".equals(status) || "MISSED".equals(status)) {
            return ResponseEntity.status(409).body(Map.of("message", "Урок уже завершён"));
        }
        java.sql.Timestamp at = (java.sql.Timestamp) l.get("scheduled_at");
        if ("PLANNED".equals(status) && at != null
                && at.toInstant().isAfter(Instant.now().plusSeconds(OPEN_BEFORE_SECONDS))) {
            return ResponseEntity.status(403).body(
                Map.of("message", "Конференция откроется за 10 минут до начала урока"));
        }

        String name = (String) u.get("name");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("role",   role);
        result.put("name",   name);
        result.put("url",    liveKit.clientUrl());
        result.put("token",  liveKit.createToken(lessonId, userId, name, role));
        return ResponseEntity.ok(result);
    }

    /* ── завершение урока: посещаемость по порогу, DONE, списание ────────── */

    @PostMapping("/{lessonId}/finish")
    public ResponseEntity<?> finish(@PathVariable long lessonId, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> u = me(auth);
        long userId = ((Number) u.get("id")).longValue();
        String role = (String) u.get("role");
        if (!("TEACHER".equals(role) || "ADMIN".equals(role) || "MANAGER".equals(role))) {
            return ResponseEntity.status(403).body(Map.of("message", "Завершить урок может только преподаватель"));
        }
        if ("TEACHER".equals(role)) {
            Integer owns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lessons WHERE id=? AND teacher_id=?", Integer.class, lessonId, userId);
            if (owns == null || owns == 0) return ResponseEntity.status(404).body(Map.of("message", "Урок не найден"));
        }

        int updated = jdbc.update(
            "UPDATE lessons SET status='DONE'::lesson_status WHERE id=? AND status IN ('PLANNED'::lesson_status,'IN_PROGRESS'::lesson_status)",
            lessonId);

        attendance.closeOpenSessions(lessonId, Instant.now());
        attendance.applyThreshold(lessonId);
        int deducted = billing.settleLesson(lessonId);   // идемпотентно: повторное завершение ничего не спишет
        liveKit.closeRoom(lessonId);

        return ResponseEntity.ok(Map.of("finished", true, "alreadyFinished", updated == 0, "deducted", deducted));
    }

    /* ── веб-хуки LiveKit: вход и выход участников ───────────────────────── */

    @PostMapping("/livekit/webhook")
    public ResponseEntity<?> webhook(@RequestBody String body,
                                     @RequestHeader(value = "Authorization", required = false) String authHeader) {
        LivekitWebhook.WebhookEvent event;
        try {
            event = liveKit.receiveWebhook(body, authHeader);
        } catch (Exception e) {
            log.warn("LiveKit webhook отклонён: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }

        Long lessonId = LiveKitService.lessonIdFromRoom(event.getRoom().getName());
        if (lessonId == null) return ResponseEntity.ok().build();
        Instant at = event.getCreatedAt() > 0 ? Instant.ofEpochSecond(event.getCreatedAt()) : Instant.now();

        switch (event.getEvent()) {
            case "participant_joined" -> {
                Long participantId = parseUserId(event.getParticipant().getIdentity());
                if (participantId != null) attendance.onJoin(lessonId, participantId, at);
            }
            case "participant_left", "participant_connection_aborted" -> {
                Long participantId = parseUserId(event.getParticipant().getIdentity());
                if (participantId != null) attendance.onLeave(lessonId, participantId, at);
            }
            case "room_finished" -> attendance.closeOpenSessions(lessonId, at);
            default -> { /* остальные события не используются */ }
        }
        return ResponseEntity.ok().build();
    }

    private static Long parseUserId(String identity) {
        try {
            return Long.parseLong(identity);
        } catch (Exception e) {
            return null;
        }
    }
}
