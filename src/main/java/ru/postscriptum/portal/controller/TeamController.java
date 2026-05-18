package ru.postscriptum.portal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class TeamController {

    // TODO: заменить на реальные данные из БД

    @GetMapping("/team")
    public ResponseEntity<?> team() {
        return ResponseEntity.ok(List.of(
            Map.of("id", 1, "name", "Софья Фролова",     "role", "Админ · основатель",      "flag", "fr", "chip", "purple", "weekHours", "—",        "capacity", 30, "total", 23, "heatmap", List.of(4,5,3,5,4,2,0)),
            Map.of("id", 2, "name", "Татьяна Кравченко", "role", "Преподаватель",            "flag", "en", "chip", "orange", "weekHours", "22 ч/нед", "capacity", 28, "total", 22, "heatmap", List.of(3,4,5,4,3,3,0)),
            Map.of("id", 3, "name", "Pierre Bouchard",   "role", "Преподаватель · носитель", "flag", "fr", "chip", "orange", "weekHours", "12 ч/нед", "capacity", 20, "total", 12, "heatmap", List.of(2,3,2,2,3,0,0)),
            Map.of("id", 4, "name", "Анна Жукова",       "role", "Менеджер",                 "flag", "",   "chip", "blue",   "weekHours", "11 заявок","capacity", 18, "total",  7, "heatmap", List.of(1,2,1,2,1,0,0)),
            Map.of("id", 5, "name", "Лаура Мартин",      "role", "Преподаватель",            "flag", "es", "chip", "orange", "weekHours", "18 ч/нед", "capacity", 24, "total", 26, "over", true, "heatmap", List.of(4,5,4,5,4,3,1)),
            Map.of("id", 6, "name", "Иван Шульц",        "role", "Преподаватель",            "flag", "de", "chip", "orange", "weekHours", "14 ч/нед", "capacity", 22, "total", 14, "heatmap", List.of(3,3,2,3,3,0,0))
        ));
    }

    @GetMapping("/leads")
    public ResponseEntity<?> leads() {
        return ResponseEntity.ok(List.of(
            Map.of("id", 1, "name", "Мария Климова",  "details", "Французский · с нуля · вечер · 2р/нед",  "receivedAt", "20 мин", "lang", "fr", "isNew", true),
            Map.of("id", 2, "name", "Артём Зайцев",   "details", "Английский · B1 · утро · 3р/нед",         "receivedAt", "2 ч",    "lang", "en", "isNew", false),
            Map.of("id", 3, "name", "Наташа Белова",  "details", "Испанский · с нуля · любое время",        "receivedAt", "вчера",  "lang", "es", "isNew", false),
            Map.of("id", 4, "name", "Олег Тихонов",   "details", "Немецкий · A2 · выходные",                "receivedAt", "2 дн",   "lang", "de", "isNew", false),
            Map.of("id", 5, "name", "Света Лебедева", "details", "Французский · B2 · вечер · носитель",     "receivedAt", "3 дн",   "lang", "fr", "isNew", false),
            Map.of("id", 6, "name", "Рома Фёдоров",   "details", "Итальянский · с нуля · вечер",             "receivedAt", "5 дн",   "lang", "it", "isNew", false),
            Map.of("id", 7, "name", "Диана Павлова",  "details", "Английский · C1 · подготовка IELTS",       "receivedAt", "1 нед",  "lang", "en", "isNew", false)
        ));
    }

    @GetMapping("/access-matrix")
    public ResponseEntity<?> accessMatrix() {
        return ResponseEntity.ok(Map.of(
            "roles", List.of("Ученик", "Родитель", "Препод.", "Менеджер", "Админ"),
            "modules", List.of(
                Map.of("name", "Расписание · свои",  "permissions", List.of("R",    "R",    "R/W",  "R/W",  "R/W")),
                Map.of("name", "Расписание · все",   "permissions", List.of("—",    "—",    "—",    "R/W",  "R/W")),
                Map.of("name", "Домашние задания",   "permissions", List.of("R/W",  "R",    "R/W",  "—",    "R/W")),
                Map.of("name", "Материалы курса",    "permissions", List.of("R",    "—",    "R/W",  "—",    "R/W")),
                Map.of("name", "Финансы · свои",     "permissions", List.of("R",    "R",    "R",    "—",    "R/W")),
                Map.of("name", "Финансы · все",      "permissions", List.of("—",    "—",    "—",    "—",    "R/W")),
                Map.of("name", "Управление ролями",  "permissions", List.of("—",    "—",    "—",    "—",    "R/W")),
                Map.of("name", "Заявки и лиды",      "permissions", List.of("—",    "—",    "—",    "R/W",  "R/W"))
            )
        ));
    }
}
