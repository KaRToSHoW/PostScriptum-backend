package ru.postscriptum.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Web Push: рассылает браузерные уведомления даже при закрытом сайте.
 * VAPID-ключи храним в БД (одни на все инстансы за балансером), самолечащий DDL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushSenderService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private static final String SUBJECT = "mailto:support@postscriptum-online.ru";

    private volatile String vapidPublic;
    private volatile PushService pushService;

    public String getPublicKey() { return vapidPublic; }

    @PostConstruct
    void init() {
        try {
            if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
            ensureSchema();
            loadOrCreateKeys();
            if (vapidPublic != null) {
                pushService = new PushService(vapidPublic, jdbc.queryForObject(
                    "SELECT private_key FROM push_config WHERE id=1", String.class), SUBJECT);
                log.info("Web Push инициализирован");
            }
        } catch (Exception e) {
            log.error("Web Push не инициализирован: {}", e.getMessage());
        }
    }

    private void ensureSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS push_subscriptions (
                id         BIGSERIAL PRIMARY KEY,
                user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                endpoint   TEXT NOT NULL UNIQUE,
                p256dh     TEXT NOT NULL,
                auth       TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""");
        jdbc.execute("CREATE TABLE IF NOT EXISTS push_config (id INT PRIMARY KEY, public_key TEXT NOT NULL, private_key TEXT NOT NULL)");
        jdbc.execute("ALTER TABLE notifications ADD COLUMN IF NOT EXISTS pushed BOOLEAN NOT NULL DEFAULT FALSE");
    }

    private void loadOrCreateKeys() throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT public_key FROM push_config WHERE id=1");
        if (rows.isEmpty()) {
            String[] kp = generateVapidKeys();
            // ON CONFLICT — на случай гонки двух инстансов за балансером
            jdbc.update("INSERT INTO push_config (id, public_key, private_key) VALUES (1, ?, ?) ON CONFLICT (id) DO NOTHING", kp[0], kp[1]);
            rows = jdbc.queryForList("SELECT public_key FROM push_config WHERE id=1");
        }
        vapidPublic = (String) rows.get(0).get("public_key");
    }

    /** Генерирует пару VAPID-ключей (P-256), возвращает [publicBase64Url, privateBase64Url]. */
    private String[] generateVapidKeys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        ECPrivateKey priv = (ECPrivateKey) kp.getPrivate();
        ECPoint w = pub.getW();
        byte[] point = new byte[65];
        point[0] = 0x04;
        System.arraycopy(toFixed(w.getAffineX(), 32), 0, point, 1, 32);
        System.arraycopy(toFixed(w.getAffineY(), 32), 0, point, 33, 32);
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return new String[]{ enc.encodeToString(point), enc.encodeToString(toFixed(priv.getS(), 32)) };
    }

    private static byte[] toFixed(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        if (b.length == len) return b;
        byte[] out = new byte[len];
        if (b.length > len) System.arraycopy(b, b.length - len, out, 0, len);
        else System.arraycopy(b, 0, out, len - b.length, b.length);
        return out;
    }

    /** Рассылает пуш всем подпискам пользователя. Протухшие подписки (404/410) удаляем. */
    public void sendToUser(long userId, String title, String body, String link) {
        if (pushService == null) return;
        List<Map<String, Object>> subs = jdbc.queryForList(
            "SELECT endpoint, p256dh, auth FROM push_subscriptions WHERE user_id=?", userId);
        if (subs.isEmpty()) return;

        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of(
                "title", title != null ? title : "Post Scriptum",
                "body",  body  != null ? body  : "",
                "url",   link  != null ? link  : "/"));
        } catch (Exception e) { return; }

        for (Map<String, Object> s : subs) {
            String endpoint = (String) s.get("endpoint");
            try {
                Notification n = new Notification(endpoint, (String) s.get("p256dh"), (String) s.get("auth"),
                                                  payload.getBytes(StandardCharsets.UTF_8));
                HttpResponse resp = pushService.send(n);
                int code = resp.getStatusLine().getStatusCode();
                if (code == 404 || code == 410) jdbc.update("DELETE FROM push_subscriptions WHERE endpoint=?", endpoint);
            } catch (Exception e) {
                log.warn("Push не отправлен ({}): {}", endpoint, e.getMessage());
            }
        }
    }

    /**
     * Раз в 15с забираем новые непроталкнутые уведомления и шлём пуши.
     * UPDATE ... RETURNING с FOR UPDATE SKIP LOCKED — атомарный «захват», чтобы за
     * балансером один и тот же пуш не ушёл дважды с разных инстансов.
     */
    @Scheduled(fixedDelayString = "15000", initialDelayString = "12000")
    public void pushPending() {
        if (pushService == null) return;
        List<Map<String, Object>> claimed;
        try {
            claimed = jdbc.queryForList("""
                UPDATE notifications SET pushed = TRUE
                WHERE id IN (
                    SELECT id FROM notifications
                    WHERE pushed = FALSE AND created_at > NOW() - INTERVAL '1 hour'
                    ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED)
                RETURNING id, user_id, title, body, link
                """);
        } catch (Exception e) {
            log.warn("Опрос уведомлений для пуша не удался: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> n : claimed) {
            sendToUser(((Number) n.get("user_id")).longValue(),
                       (String) n.get("title"), (String) n.get("body"), (String) n.get("link"));
        }
    }
}
