package ru.postscriptum.portal.service;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.WebhookReceiver;
import livekit.LivekitWebhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Интеграция с медиасервером LiveKit: токены доступа к комнате занятия,
 * закрытие комнаты и проверка подписи веб-хуков.
 * Комната занятия называется {@code lesson-<id>}, идентификатор участника — id пользователя.
 */
@Slf4j
@Service
public class LiveKitService {

    private static final String ROOM_PREFIX = "lesson-";

    /** Адрес для браузера (wss://...). */
    @Value("${app.livekit.url:}")
    private String url;
    /** Адрес серверного API (https://... или http://... внутри сети). Если пусто — вычисляется из url. */
    @Value("${app.livekit.api-url:}")
    private String apiUrl;
    @Value("${app.livekit.api-key:}")
    private String apiKey;
    @Value("${app.livekit.api-secret:}")
    private String apiSecret;

    public boolean isConfigured() {
        return notBlank(url) && notBlank(apiKey) && notBlank(apiSecret);
    }

    public String clientUrl() {
        return url;
    }

    public static String roomName(long lessonId) {
        return ROOM_PREFIX + lessonId;
    }

    /** id занятия из имени комнаты или null, если комната не наша. */
    public static Long lessonIdFromRoom(String roomName) {
        if (roomName == null || !roomName.startsWith(ROOM_PREFIX)) return null;
        try {
            return Long.parseLong(roomName.substring(ROOM_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Токен доступа к комнате занятия. Публиковать медиа может любой участник урока,
     * отправлять данные (чат комнаты) — тоже; права проверены до вызова.
     */
    public String createToken(long lessonId, long userId, String name, String role) {
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setIdentity(String.valueOf(userId));
        token.setName(name != null ? name : String.valueOf(userId));
        token.setMetadata("{\"role\":\"" + role + "\"}");
        token.addGrants(
            new RoomJoin(true),
            new RoomName(roomName(lessonId)),
            new CanPublish(true),
            new CanSubscribe(true),
            new CanPublishData(true));
        return token.toJwt();
    }

    /** Закрывает комнату занятия: все участники отключаются. Ошибки не критичны. */
    public void closeRoom(long lessonId) {
        if (!isConfigured()) return;
        try {
            RoomServiceClient client = RoomServiceClient.createClient(serverApiUrl(), apiKey, apiSecret);
            client.deleteRoom(roomName(lessonId)).execute();
        } catch (Exception e) {
            log.warn("LiveKit: не удалось закрыть комнату занятия {}: {}", lessonId, e.getMessage());
        }
    }

    /** Проверяет подпись и разбирает веб-хук. Бросает исключение, если подпись неверна. */
    public LivekitWebhook.WebhookEvent receiveWebhook(String body, String authHeader) {
        return new WebhookReceiver(apiKey, apiSecret).receive(body, authHeader);
    }

    private String serverApiUrl() {
        if (notBlank(apiUrl)) return apiUrl;
        return url.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
