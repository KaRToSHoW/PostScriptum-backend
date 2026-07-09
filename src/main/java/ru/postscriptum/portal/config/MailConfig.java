package ru.postscriptum.portal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Настройка отправки почты из простых env-переменных (SMTP_HOST/PORT/USERNAME/PASSWORD).
 *
 * Свой бин, потому что на сервере application.yml в jar не попадает (в .gitignore) —
 * так конфиг одинаково читается и локально, и в проде без длинных SPRING_MAIL_* имён.
 * Если SMTP_HOST не задан — письма не шлём (PasswordResetService пишет ссылку в лог).
 */
@Configuration
public class MailConfig {

    @Value("${SMTP_HOST:}")     private String host;
    @Value("${SMTP_PORT:587}")  private int    port;
    @Value("${SMTP_USERNAME:}") private String username;
    @Value("${SMTP_PASSWORD:}") private String password;

    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(username != null && !username.isBlank()));
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");
        return sender;
    }
}
