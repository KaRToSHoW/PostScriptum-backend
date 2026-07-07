package ru.postscriptum.portal.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.postscriptum.portal.model.User;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    private SecretKey key;

    @PostConstruct
    public void init() {
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("role",     user.getRole().name().toLowerCase())
                .claim("name",     user.getName())
                .claim("initials", user.getInitials())
                .claim("subtitle", user.getSubtitle())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public String getEmailFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Короткоживущий подписанный state-токен для OAuth (защита от CSRF).
     * Не требует хранения сессии — вся защита в подписи и сроке жизни.
     */
    public String generateStateToken(String provider) {
        return Jwts.builder()
                .subject("oauth-state")
                .claim("provider", provider)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000)) // 10 минут
                .signWith(key)
                .compact();
    }

    /** Проверяет подпись/срок state-токена и что он выпущен для того же провайдера. */
    public boolean validateStateToken(String token, String provider) {
        try {
            Claims claims = parseClaims(token);
            return "oauth-state".equals(claims.getSubject())
                    && provider.equals(claims.get("provider", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
