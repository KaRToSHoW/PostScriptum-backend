package ru.postscriptum.portal.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.postscriptum.portal.dto.AuthResponse;
import ru.postscriptum.portal.service.AuthService;
import ru.postscriptum.portal.service.OAuthService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Вход через соцсети. Оба эндпоинта отдают 302-редирект (браузер ходит по ним напрямую),
 * поэтому находятся под /api/auth/** — этот префикс уже открыт в SecurityConfig.
 */
@RestController
@RequestMapping("/api/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oauthService;
    private final AuthService  authService;

    /** Старт: строим URL провайдера и отправляем туда браузер. */
    @GetMapping("/{provider}")
    public void start(@PathVariable String provider, HttpServletResponse res) throws IOException {
        if (!oauthService.isSupported(provider)) {
            res.sendRedirect(oauthService.frontendUrl() + "/oauth/callback?error=provider");
            return;
        }
        res.sendRedirect(oauthService.buildAuthorizeUrl(provider));
    }

    /** Возврат от провайдера: меняем code на профиль, выдаём JWT и уводим на фронт. */
    @GetMapping("/{provider}/callback")
    public void callback(@PathVariable String provider,
                         @RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error,
                         HttpServletResponse res) throws IOException {
        String frontend = oauthService.frontendUrl();
        try {
            if (error != null || code == null) {
                res.sendRedirect(frontend + "/oauth/callback?error=denied");
                return;
            }
            if (!oauthService.validateState(provider, state)) {
                res.sendRedirect(frontend + "/oauth/callback?error=state");
                return;
            }

            OAuthService.OAuthUser user = oauthService.fetchUser(provider, code);
            AuthResponse auth = authService.oauthLogin(user.email(), user.name());

            String token = URLEncoder.encode(auth.token(), StandardCharsets.UTF_8);
            res.sendRedirect(frontend + "/oauth/callback?token=" + token);
        } catch (Exception e) {
            res.sendRedirect(frontend + "/oauth/callback?error=oauth");
        }
    }
}
