package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Создание лидов не только с публичной формы: при самостоятельной
 * регистрации ученика заводим заявку «на распределение», чтобы менеджер
 * увидел нового человека, связался и прикрепил к преподавателю.
 */
@Service
@RequiredArgsConstructor
public class LeadService {

    private final JdbcTemplate jdbc;

    /**
     * Заявка на распределение нового ученика + уведомление менеджерам/админам.
     * Любая ошибка глотается: регистрация важнее заявки.
     */
    public void createRegistrationLead(String name, String email, String phone, String source) {
        try {
            jdbc.update(
                "INSERT INTO leads (name, phone, email, notes, source, status, received_at) " +
                "VALUES (?, ?, ?, ?, ?, 'NEW'::lead_status, NOW())",
                name, phone, email,
                "Новый ученик зарегистрировался сам — связаться и распределить к преподавателю",
                source);

            jdbc.update("""
                INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
                SELECT u.id, 'NEW_MESSAGE'::notification_type, 'Новый ученик — нужно распределение',
                       ?, '/leads', false, NOW()
                FROM users u
                WHERE u.role IN ('MANAGER'::user_role, 'ADMIN'::user_role) AND u.is_active = true
                """, name + " · " + email);
        } catch (Exception ignored) { /* заявка вторична — не роняем регистрацию */ }
    }
}
