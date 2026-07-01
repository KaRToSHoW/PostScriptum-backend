package ru.postscriptum.portal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Шифрование сообщений чата на уровне БД (AES-256-GCM).
 *
 * Защищает содержимое чатов от утечки самой базы — если БД скомпрометирована
 * (дамп, бэкап, доступ к диску), переписка не читается в открытом виде.
 * Это не end-to-end: бэкенд по-прежнему может расшифровать сообщение для отдачи
 * через API (нужно для поиска/уведомлений/работы приложения в целом).
 *
 * Формат значения в колонке messages.body: "enc:v1:<base64(iv)>:<base64(ciphertext)>"
 * Старые незашифрованные строки (без префикса "enc:v1:") отдаются как есть —
 * миграция существующих данных не требуется.
 */
@Service
public class MessageCryptoService {

    private static final String PREFIX = "enc:v1:";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MessageCryptoService(@Value("${app.message.encryption-key}") String rawKey) {
        // Ключ любой длины сводим к 256 бит через SHA-256, чтобы не требовать от .env
        // ровно 32-байтную base64-строку — подходит любая секретная фраза.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(rawKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось инициализировать ключ шифрования чата", e);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            return PREFIX
                + Base64.getEncoder().encodeToString(iv) + ":"
                + Base64.getEncoder().encodeToString(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка шифрования сообщения", e);
        }
    }

    /** Расшифровывает значение; если оно не несёт нашего префикса — возвращает как есть (старые данные). */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            byte[] iv         = Base64.getDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // повреждённые/чужие данные — не валим запрос, просто показываем заглушку
            return "[не удалось расшифровать сообщение]";
        }
    }
}
