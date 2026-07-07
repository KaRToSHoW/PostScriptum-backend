package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Собственная система видеоконференций (без внешних ссылок).
 *
 * Медиа идёт напрямую между участниками (WebRTC), бэкенд выполняет роль
 * сигнального сервера: очереди offer/answer/ICE-кандидатов + presence.
 * Вход в комнату фиксирует присутствие ученика, завершение урока преподавателем
 * ставит статус DONE и автоматически списывает занятие с абонемента.
 */
@RestController
@RequestMapping("/api/conference")
@RequiredArgsConstructor
public class ConferenceController {

    private final JdbcTemplate jdbc;

    @Value("${app.turn.url:}")
    private String turnUrl;
    @Value("${app.turn.user:}")
    private String turnUser;
    @Value("${app.turn.password:}")
    private String turnPassword;

    /** ICE-конфигурация для клиентов: STUN всегда, TURN — если настроен на сервере. */
    @GetMapping("/ice")
    public ResponseEntity<?> ice(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        List<Map<String, Object>> servers = new ArrayList<>();
        servers.add(Map.of("urls", "stun:stun.l.google.com:19302"));
        if (turnUrl != null && !turnUrl.isBlank() && turnPassword != null && !turnPassword.isBlank()) {
            servers.add(Map.of(
                "urls", turnUrl,
                "username", turnUser,
                "credential", turnPassword));
        }
        return ResponseEntity.ok(Map.of("iceServers", servers));
    }

    /* ── in-memory сигналинг ─────────────────────────────────────────────── */

    private static final long PRESENCE_TTL_MS = 12_000;

    /** lessonId → userId → очередь сигналов для этого участника */
    private final Map<Long, Map<Long, ConcurrentLinkedQueue<Map<String, Object>>>> queues = new ConcurrentHashMap<>();
    /** lessonId → userId → отметка последнего опроса (presence) */
    private final Map<Long, Map<Long, Long>> presence = new ConcurrentHashMap<>();

    private Map<Long, ConcurrentLinkedQueue<Map<String, Object>>> roomQueues(long lessonId) {
        return queues.computeIfAbsent(lessonId, k -> new ConcurrentHashMap<>());
    }

    private Map<Long, Long> roomPresence(long lessonId) {
        return presence.computeIfAbsent(lessonId, k -> new ConcurrentHashMap<>());
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private Map<String, Object> me(Authentication auth) {
        return jdbc.queryForMap("SELECT id, name, initials, role FROM users WHERE email = ?", auth.getName());
    }

    private boolean isParticipant(long lessonId, long userId, String role) {
        Integer cnt;
        if ("TEACHER".equals(role)) {
            cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lessons WHERE id=? AND teacher_id=?", Integer.class, lessonId, userId);
        } else if ("ADMIN".equals(role) || "MANAGER".equals(role)) {
            cnt = jdbc.queryForObject("SELECT COUNT(*) FROM lessons WHERE id=?", Integer.class, lessonId);
        } else {
            cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lesson_students WHERE lesson_id=? AND student_id=?", Integer.class, lessonId, userId);
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

    /* ── информация об уроке: участники + материалы ──────────────────────── */

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
                       t.id AS teacher_id, t.name AS teacher, t.initials AS teacher_initials
                FROM lessons l
                JOIN languages lang ON lang.id = l.language_id
                JOIN users t ON t.id = l.teacher_id
                WHERE l.id = ?
                """, lessonId);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("message", "Урок не найден"));
        }

        List<Map<String, Object>> students = jdbc.queryForList("""
            SELECT u.id, u.name, u.initials, ls.attended
            FROM lesson_students ls JOIN users u ON u.id = ls.student_id
            WHERE ls.lesson_id = ? ORDER BY u.name
            """, lessonId);

        List<Map<String, Object>> materials = jdbc.queryForList("""
            SELECT h.id, h.title, h.description, h.attachment_url, h.status::text AS status, h.due_at
            FROM homework h
            WHERE h.lesson_id = ?
            ORDER BY h.created_at ASC
            """, lessonId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lesson",    lesson);
        result.put("students",  students);
        result.put("materials", materials);
        result.put("myId",      userId);
        result.put("myRole",    role);
        result.put("myName",    u.get("name"));
        return ResponseEntity.ok(result);
    }

    /* ── вход в комнату: фиксируем присутствие ───────────────────────────── */

    @PostMapping("/{lessonId}/join")
    public ResponseEntity<?> join(@PathVariable long lessonId, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> u = me(auth);
        long userId = ((Number) u.get("id")).longValue();
        String role = (String) u.get("role");
        if (!isParticipant(lessonId, userId, role)) {
            return ResponseEntity.status(403).body(Map.of("message", "Вы не участник этого урока"));
        }

        // комната открывается за 10 минут до начала урока
        try {
            Map<String, Object> l = jdbc.queryForMap(
                "SELECT scheduled_at, status::text AS status FROM lessons WHERE id=?", lessonId);
            if ("PLANNED".equals(l.get("status"))) {
                java.sql.Timestamp at = (java.sql.Timestamp) l.get("scheduled_at");
                if (at != null && at.toInstant().isAfter(java.time.Instant.now().plusSeconds(10 * 60))) {
                    return ResponseEntity.status(403).body(
                        Map.of("message", "Конференция откроется за 10 минут до начала урока"));
                }
            }
        } catch (Exception ignored) {}

        if ("STUDENT".equals(role)) {
            jdbc.update("UPDATE lesson_students SET attended=true WHERE lesson_id=? AND student_id=?", lessonId, userId);
        }
        jdbc.update(
            "UPDATE lessons SET status='IN_PROGRESS'::lesson_status WHERE id=? AND status='PLANNED'::lesson_status",
            lessonId);

        roomQueues(lessonId).computeIfAbsent(userId, k -> new ConcurrentLinkedQueue<>());
        roomPresence(lessonId).put(userId, System.currentTimeMillis());

        return ResponseEntity.ok(Map.of("userId", userId, "role", role, "name", u.get("name")));
    }

    /* ── сигналинг WebRTC ────────────────────────────────────────────────── */

    @PostMapping("/{lessonId}/signal")
    public ResponseEntity<?> signal(@PathVariable long lessonId, Authentication auth,
                                    @RequestBody Map<String, Object> body) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> u = me(auth);
        long userId = ((Number) u.get("id")).longValue();
        String role = (String) u.get("role");
        if (!isParticipant(lessonId, userId, role)) return ResponseEntity.status(403).build();

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("from",     userId);
        msg.put("fromName", u.get("name"));
        msg.put("type",     body.get("type"));
        msg.put("payload",  body.get("payload"));
        msg.put("ts",       System.currentTimeMillis());

        Object toRaw = body.get("to");
        Map<Long, ConcurrentLinkedQueue<Map<String, Object>>> room = roomQueues(lessonId);
        if (toRaw != null) {
            long to = ((Number) toRaw).longValue();
            room.computeIfAbsent(to, k -> new ConcurrentLinkedQueue<>()).add(msg);
        } else {
            for (Map.Entry<Long, ConcurrentLinkedQueue<Map<String, Object>>> e : room.entrySet()) {
                if (e.getKey() != userId) e.getValue().add(msg);
            }
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{lessonId}/signals")
    public ResponseEntity<?> signals(@PathVariable long lessonId, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> u = me(auth);
        long userId = ((Number) u.get("id")).longValue();
        String role = (String) u.get("role");
        if (!isParticipant(lessonId, userId, role)) return ResponseEntity.status(403).build();

        // heartbeat
        roomPresence(lessonId).put(userId, System.currentTimeMillis());
        roomQueues(lessonId).computeIfAbsent(userId, k -> new ConcurrentLinkedQueue<>());

        // забираем накопившиеся сигналы
        List<Map<String, Object>> out = new ArrayList<>();
        ConcurrentLinkedQueue<Map<String, Object>> q = roomQueues(lessonId).get(userId);
        Map<String, Object> m;
        while ((m = q.poll()) != null) out.add(m);

        // кто сейчас в комнате
        long now = System.currentTimeMillis();
        List<Long> online = new ArrayList<>();
        for (Map.Entry<Long, Long> e : roomPresence(lessonId).entrySet()) {
            if (now - e.getValue() < PRESENCE_TTL_MS) online.add(e.getKey());
        }

        String status = null;
        try {
            status = jdbc.queryForObject("SELECT status::text FROM lessons WHERE id=?", String.class, lessonId);
        } catch (Exception ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signals", out);
        result.put("online",  online);
        result.put("status",  status);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{lessonId}/leave")
    public ResponseEntity<?> leave(@PathVariable long lessonId, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Map<String, Object> u = me(auth);
        long userId = ((Number) u.get("id")).longValue();
        roomPresence(lessonId).remove(userId);
        Map<Long, ConcurrentLinkedQueue<Map<String, Object>>> room = roomQueues(lessonId);
        Map<String, Object> msg = Map.of(
            "from", userId, "fromName", u.get("name"), "type", "leave", "ts", System.currentTimeMillis());
        for (Map.Entry<Long, ConcurrentLinkedQueue<Map<String, Object>>> e : room.entrySet()) {
            if (e.getKey() != userId) e.getValue().add(msg);
        }
        room.remove(userId);
        return ResponseEntity.ok().build();
    }

    /* ── завершение урока преподавателем: DONE + автосписание ────────────── */

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

        // защита от повторного списания: DONE ставим только один раз
        int updated = jdbc.update(
            "UPDATE lessons SET status='DONE'::lesson_status WHERE id=? AND status IN ('PLANNED'::lesson_status,'IN_PROGRESS'::lesson_status)",
            lessonId);
        if (updated == 0) {
            return ResponseEntity.ok(Map.of("alreadyFinished", true));
        }

        Map<String, Object> lesson = jdbc.queryForMap(
            "SELECT teacher_id, language_id, enrollment_id FROM lessons WHERE id=?", lessonId);
        long teacherId = ((Number) lesson.get("teacher_id")).longValue();
        long languageId = ((Number) lesson.get("language_id")).longValue();
        Object enrollmentId = lesson.get("enrollment_id");

        List<Map<String, Object>> students = jdbc.queryForList(
            "SELECT student_id, attended FROM lesson_students WHERE lesson_id=?", lessonId);

        int deducted = 0;
        for (Map<String, Object> s : students) {
            long studentId = ((Number) s.get("student_id")).longValue();

            // неотмеченных считаем отсутствовавшими (присутствие фиксируется при входе в конференцию)
            if (s.get("attended") == null) {
                jdbc.update("UPDATE lesson_students SET attended=false WHERE lesson_id=? AND student_id=?",
                    lessonId, studentId);
            }

            // урок проведён → списываем занятие с абонемента (сначала тот, что истекает раньше)
            int rows = jdbc.update("""
                UPDATE subscriptions SET lessons_used = lessons_used + 1
                WHERE id = (
                    SELECT id FROM subscriptions
                    WHERE student_id = ? AND status = 'ACTIVE' AND lessons_used < lessons_total
                    ORDER BY end_date ASC NULLS LAST LIMIT 1
                )
                """, studentId);
            deducted += rows;

            // прогресс по записи на курс
            if (enrollmentId != null) {
                jdbc.update("UPDATE enrollments SET lessons_done = lessons_done + 1 WHERE id=?",
                    ((Number) enrollmentId).longValue());
            } else {
                jdbc.update("""
                    UPDATE enrollments SET lessons_done = lessons_done + 1
                    WHERE id = (
                        SELECT id FROM enrollments
                        WHERE student_id=? AND teacher_id=? AND language_id=? AND is_active=true
                        ORDER BY id DESC LIMIT 1
                    )
                    """, studentId, teacherId, languageId);
            }
        }

        // сообщаем всем в комнате, что урок завершён
        Map<Long, ConcurrentLinkedQueue<Map<String, Object>>> room = roomQueues(lessonId);
        Map<String, Object> msg = Map.of(
            "from", userId, "fromName", u.get("name"), "type", "finished", "ts", System.currentTimeMillis());
        for (Map.Entry<Long, ConcurrentLinkedQueue<Map<String, Object>>> e : room.entrySet()) {
            if (e.getKey() != userId) e.getValue().add(msg);
        }

        return ResponseEntity.ok(Map.of("finished", true, "deducted", deducted));
    }
}
