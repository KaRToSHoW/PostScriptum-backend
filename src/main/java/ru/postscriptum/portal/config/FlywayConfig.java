package ru.postscriptum.portal.config;

import org.flywaydb.core.api.exception.FlywayValidateException;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    /**
     * Если схема уже существует (старый деплой без Flyway) — делаем baseline,
     * затем запускаем миграции. Иначе запускаем как обычно.
     */
    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            try {
                flyway.migrate();
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("non-empty schema")) {
                    flyway.baseline();
                    flyway.migrate();
                } else {
                    throw e;
                }
            }
        };
    }
}
