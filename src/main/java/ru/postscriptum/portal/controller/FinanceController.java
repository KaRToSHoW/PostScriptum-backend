package ru.postscriptum.portal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/finance")
public class FinanceController {

    // TODO: заменить на реальные агрегации из БД по указанному периоду

    @GetMapping
    public ResponseEntity<?> summary(@RequestParam(defaultValue = "MONTH") String period) {
        return ResponseEntity.ok(Map.of(
            "period", period,
            "kpi", List.of(
                Map.of("l", "Выручка · май",        "v", "₽ 642 500", "d", "+18% к апрелю", "up", true),
                Map.of("l", "Активных абонементов", "v", "184",        "d", "+22 за месяц",  "up", true),
                Map.of("l", "Средний чек",          "v", "₽ 13 800",   "d", "+₽ 400",        "up", true),
                Map.of("l", "Просрочки",            "v", "7",          "d", "₽ 96 800",      "up", false),
                Map.of("l", "Возвраты",             "v", "2",          "d", "−1 к апрелю",   "up", true)
            ),
            "revenue", List.of(
                Map.of("m", "ЯНВ", "v", List.of(40, 30, 12, 8, 5)),
                Map.of("m", "ФЕВ", "v", List.of(48, 32, 13, 8, 6)),
                Map.of("m", "МАР", "v", List.of(55, 36, 15, 10, 7)),
                Map.of("m", "АПР", "v", List.of(60, 40, 16, 11, 8)),
                Map.of("m", "МАЙ", "v", List.of(72, 46, 18, 13, 10), "current", true),
                Map.of("m", "ИЮН", "v", List.of(25, 16, 7, 4, 3),    "forecast", true)
            ),
            "subscriptions", Map.of(
                "active", 184,
                "labels", List.of("Французский", "Английский", "Немецкий", "Испанский", "Итальянский"),
                "counts", List.of(72, 46, 18, 13, 10)
            )
        ));
    }

    @GetMapping("/payments")
    public ResponseEntity<?> payments(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var content = List.of(
            Map.of("id", 1, "student", "Анна Соколова",   "subscription", "Французский · 8 уроков",   "amount", "₽ 13 600", "date", "12.05.2026", "method", "Карта",   "status", "paid"),
            Map.of("id", 2, "student", "Михаил Орлов",    "subscription", "Английский · 4 урока",     "amount", "₽ 7 200",  "date", "11.05.2026", "method", "СБП",     "status", "paid"),
            Map.of("id", 3, "student", "Лиза Климова",    "subscription", "Французский · 8 уроков",   "amount", "₽ 13 600", "date", "10.05.2026", "method", "Карта",   "status", "paid"),
            Map.of("id", 4, "student", "Кирилл Волков",   "subscription", "Немецкий · 4 урока",       "amount", "₽ 7 200",  "date", "09.05.2026", "method", "Карта",   "status", "overdue"),
            Map.of("id", 5, "student", "Юля Новикова",    "subscription", "Испанский · 8 уроков",     "amount", "₽ 12 400", "date", "08.05.2026", "method", "СБП",     "status", "paid"),
            Map.of("id", 6, "student", "Денис Нечаев",    "subscription", "Немецкий · 8 уроков",      "amount", "₽ 13 600", "date", "07.05.2026", "method", "Карта",   "status", "refunded"),
            Map.of("id", 7, "student", "Ирина Соколова",  "subscription", "Французский · 4 урока",    "amount", "₽ 7 200",  "date", "06.05.2026", "method", "Карта",   "status", "paid")
        );

        return ResponseEntity.ok(Map.of(
            "content",       content,
            "page",          page,
            "size",          size,
            "totalElements", 142,
            "totalPages",    8
        ));
    }
}
