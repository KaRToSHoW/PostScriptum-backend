package ru.postscriptum.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import ru.postscriptum.portal.security.JwtTokenProvider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Вход через соцсети (VK и Яндекс) по OAuth 2.0 (Authorization Code).
 *
 * Поток:
 *   1) фронт открывает GET /api/auth/oauth/{provider} → сюда, строим URL авторизации → редирект к провайдеру
 *   2) провайдер возвращает пользователя на /api/auth/oauth/{provider}/callback?code=...
 *   3) меняем code на access_token, запрашиваем профиль → нормализованный OAuthUser
 *
 * client-id / client-secret / redirect-uri задаются в application.yml (см. app.oauth.*).
 */
@Service
@RequiredArgsConstructor
public class OAuthService {

    private final JwtTokenProvider tokenProvider;
    private final RestClient rest = RestClient.create();

    @Value("${app.oauth.frontend-url}")     private String frontendUrl;

    @Value("${app.oauth.vk.client-id:}")     private String vkClientId;
    @Value("${app.oauth.vk.client-secret:}") private String vkClientSecret;
    @Value("${app.oauth.vk.redirect-uri:}")  private String vkRedirectUri;

    @Value("${app.oauth.yandex.client-id:}")     private String yandexClientId;
    @Value("${app.oauth.yandex.client-secret:}") private String yandexClientSecret;
    @Value("${app.oauth.yandex.redirect-uri:}")  private String yandexRedirectUri;

    /** Нормализованный профиль из соцсети. */
    public record OAuthUser(String email, String name) {}

    public String frontendUrl() {
        return frontendUrl;
    }

    public boolean isSupported(String provider) {
        return "vk".equals(provider) || "yandex".equals(provider);
    }

    // ─── шаг 1: URL авторизации у провайдера ────────────────────────────────

    public String buildAuthorizeUrl(String provider) {
        String state = tokenProvider.generateStateToken(provider);
        return switch (provider) {
            case "vk" -> "https://oauth.vk.com/authorize"
                    + "?client_id="     + vkClientId
                    + "&redirect_uri="  + enc(vkRedirectUri)
                    + "&display=page"
                    + "&scope=email"
                    + "&response_type=code"
                    + "&v=5.199"
                    + "&state="         + enc(state);
            case "yandex" -> "https://oauth.yandex.ru/authorize"
                    + "?response_type=code"
                    + "&client_id="     + yandexClientId
                    + "&redirect_uri="  + enc(yandexRedirectUri)
                    + "&state="         + enc(state);
            default -> throw new IllegalArgumentException("Неизвестный провайдер: " + provider);
        };
    }

    public boolean validateState(String provider, String state) {
        return state != null && tokenProvider.validateStateToken(state, provider);
    }

    // ─── шаг 2-3: code → токен → профиль ────────────────────────────────────

    public OAuthUser fetchUser(String provider, String code) {
        return switch (provider) {
            case "vk"     -> fetchVkUser(code);
            case "yandex" -> fetchYandexUser(code);
            default -> throw new IllegalArgumentException("Неизвестный провайдер: " + provider);
        };
    }

    private OAuthUser fetchVkUser(String code) {
        // VK возвращает access_token, user_id и (если дано разрешение) email одним ответом
        JsonNode token = rest.get()
                .uri("https://oauth.vk.com/access_token"
                        + "?client_id="     + vkClientId
                        + "&client_secret=" + vkClientSecret
                        + "&redirect_uri="  + enc(vkRedirectUri)
                        + "&code="          + enc(code))
                .retrieve()
                .body(JsonNode.class);

        if (token == null || token.hasNonNull("error")) {
            throw new IllegalStateException("VK: не удалось получить токен");
        }
        String accessToken = token.path("access_token").asText();
        long   userId      = token.path("user_id").asLong();
        String email       = token.path("email").asText(null);

        // Имя тянем отдельным запросом к API VK
        String name = "Пользователь VK";
        try {
            JsonNode profile = rest.get()
                    .uri("https://api.vk.com/method/users.get"
                            + "?user_ids="     + userId
                            + "&fields=first_name,last_name"
                            + "&access_token=" + accessToken
                            + "&v=5.199")
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode u = profile.path("response").path(0);
            String first = u.path("first_name").asText("");
            String last  = u.path("last_name").asText("");
            String full  = (first + " " + last).trim();
            if (!full.isEmpty()) name = full;
        } catch (Exception ignored) { /* оставим имя по умолчанию */ }

        // если email не выдан — синтезируем стабильный, чтобы аккаунт не задваивался
        if (email == null || email.isBlank()) email = "vk" + userId + "@vk.oauth";
        return new OAuthUser(email, name);
    }

    private OAuthUser fetchYandexUser(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type",    "authorization_code");
        form.add("code",          code);
        form.add("client_id",     yandexClientId);
        form.add("client_secret", yandexClientSecret);
        if (yandexRedirectUri != null && !yandexRedirectUri.isBlank()) {
            form.add("redirect_uri", yandexRedirectUri);
        }

        JsonNode token = rest.post()
                .uri("https://oauth.yandex.ru/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        if (token == null || !token.hasNonNull("access_token")) {
            throw new IllegalStateException("Яндекс: не удалось получить токен");
        }
        String accessToken = token.path("access_token").asText();

        JsonNode info = rest.get()
                .uri("https://login.yandex.ru/info?format=json")
                .header("Authorization", "OAuth " + accessToken)
                .retrieve()
                .body(JsonNode.class);

        String email = info.path("default_email").asText(null);
        String name  = info.path("real_name").asText(null);
        if (name == null || name.isBlank()) name = info.path("display_name").asText(null);
        if (name == null || name.isBlank()) name = "Пользователь Яндекс";

        if (email == null || email.isBlank()) {
            email = "ya" + info.path("id").asText("user") + "@yandex.oauth";
        }
        return new OAuthUser(email, name);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
