package ru.postscriptum.portal.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    /**
     * Стратегия для перехода старой БД (без истории Flyway) на управление Flyway:
     * 1. repair()  — очищает любые FAILED-миграции из предыдущих неудачных запусков
     * 2. migrate() — пробует применить миграции
     * 3. Если схема непустая и нет таблицы истории → baseline() → migrate()
     */
    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            try {
                flyway.repair();
            } catch (Exception ignored) {
                // repair может упасть если история ещё не создана — игнорируем
            }
            try {
                flyway.migrate();
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("non-empty schema")) {
                    flyway.baseline();
                    flyway.migrate();
                } else {
                    throw e;
                }
            }
        };
    }
}
