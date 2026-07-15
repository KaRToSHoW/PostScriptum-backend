package ru.postscriptum.portal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public ResponseEntity<?> get(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();

        Long me = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, auth.getName());

        // Ensure a row exists
        Integer exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_settings WHERE user_id = ?", Integer.class, me);
        if (exists == null || exists == 0) {
            jdbc.update(
                "INSERT INTO user_settings (user_id, notification_email, notification_push, notification_sms, " +
                "reminder_hours_before, theme, interface_locale, updated_at) VALUES (?,true,true,false,24,'light','ru',NOW())",
                me);
        }

        return ResponseEntity.ok(toSettingsMap(readSettings(me)));
    }

    @PutMapping
    public ResponseEntity<?> update(Authentication auth, @RequestBody Map<String, Object> body) {
        if (auth == null) return ResponseEntity.status(401).build();

        Long me = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, auth.getName());

        Boolean notifEmail = body.get("notificationEmail") != null ? (Boolean) body.get("notificationEmail") : true;
        Boolean notifPush  = body.get("notificationPush")  != null ? (Boolean) body.get("notificationPush")  : true;
        Boolean notifSms   = body.get("notificationSms")   != null ? (Boolean) body.get("notificationSms")   : false;
        // время напоминания в минутах (fallback со старого поля в часах)
        Integer reminderMin = body.get("reminderMinutesBefore") != null ? ((Number) body.get("reminderMinutesBefore")).intValue()
                            : body.get("reminderHoursBefore") != null ? ((Number) body.get("reminderHoursBefore")).intValue() * 60
                            : 15;
        if (reminderMin < 1) reminderMin = 1;
        if (reminderMin > 2880) reminderMin = 2880;   // максимум 48 часов
        int reminderH = Math.max(0, Math.round(reminderMin / 60f));
        String  theme      = body.get("theme")             != null ? (String) body.get("theme")             : "light";
        String  locale     = body.get("interfaceLocale")   != null ? (String) body.get("interfaceLocale")   : "ru";

        jdbc.update("""
            INSERT INTO user_settings (user_id, notification_email, notification_push, notification_sms,
                reminder_hours_before, reminder_minutes_before, theme, interface_locale, updated_at)
            VALUES (?,?,?,?,?,?,?,?,NOW())
            ON CONFLICT (user_id) DO UPDATE SET
                notification_email = EXCLUDED.notification_email,
                notification_push  = EXCLUDED.notification_push,
                notification_sms   = EXCLUDED.notification_sms,
                reminder_hours_before   = EXCLUDED.reminder_hours_before,
                reminder_minutes_before = EXCLUDED.reminder_minutes_before,
                theme              = EXCLUDED.theme,
                interface_locale   = EXCLUDED.interface_locale,
                updated_at         = NOW()
            """, me, notifEmail, notifPush, notifSms, reminderH, reminderMin, theme, locale);

        return ResponseEntity.ok(toSettingsMap(readSettings(me)));
    }

    private Map<String, Object> readSettings(Long userId) {
        return jdbc.queryForMap(
            "SELECT notification_email, notification_push, notification_sms, " +
            "reminder_hours_before, reminder_minutes_before, theme, interface_locale " +
            "FROM user_settings WHERE user_id = ?", userId);
    }

    private Map<String, Object> toSettingsMap(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("notificationEmail",     row.get("notification_email"));
        out.put("notificationPush",      row.get("notification_push"));
        out.put("notificationSms",       row.get("notification_sms"));
        out.put("reminderHoursBefore",   row.get("reminder_hours_before"));
        out.put("reminderMinutesBefore", row.get("reminder_minutes_before"));
        out.put("theme",                 row.get("theme"));
        out.put("interfaceLocale",       row.get("interface_locale"));
        return out;
    }
}
