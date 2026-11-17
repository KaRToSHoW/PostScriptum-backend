package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.ZoneId;

/**
 * Часовой пояс пользователя (users.timezone, выбирается в профиле).
 * Время в БД хранится в UTC (timestamptz); в этот пояс оно переводится при показе
 * и из него — при вводе (преподаватель назначает урок «на 18:00» по своему времени).
 */
@Service
@RequiredArgsConstructor
public class UserZoneService {

    public static final ZoneId DEFAULT = ZoneId.of("Europe/Moscow");
    private static final String REQUEST_KEY = UserZoneService.class.getName() + ".zone";

    private final JdbcTemplate jdbc;

    /** Пояс текущего пользователя; в пределах одного HTTP-запроса читается из БД один раз. */
    public ZoneId current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return DEFAULT;

        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST) instanceof ZoneId cached) {
            return cached;
        }
        ZoneId zone = parse(query("SELECT timezone FROM users WHERE email = ?", auth.getName()));
        if (attrs != null) attrs.setAttribute(REQUEST_KEY, zone, RequestAttributes.SCOPE_REQUEST);
        return zone;
    }

    /** Пояс конкретного пользователя (например, получателя уведомления). */
    public ZoneId of(long userId) {
        return parse(query("SELECT timezone FROM users WHERE id = ?", userId));
    }

    private String query(String sql, Object arg) {
        try {
            return jdbc.queryForObject(sql, String.class, arg);
        } catch (Exception e) {
            return null;
        }
    }

    private static ZoneId parse(String tz) {
        if (tz == null || tz.isBlank()) return DEFAULT;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return DEFAULT;
        }
    }
}
