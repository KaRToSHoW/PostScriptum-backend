package ru.postscriptum.portal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    // TODO: заменить на вызовы сервисного слоя с реальными данными из БД

    @GetMapping("/student")
    public ResponseEntity<?> student() {
        return ResponseEntity.ok(Map.of(
            "nextLesson", Map.of(
                "date", "12", "dayLabel", "ВТ", "time", "18:30",
                "teacher", "Софья Фролова", "topic", "Conditionnel présent — мечтаем по-французски",
                "lang", "fr", "zoomUrl", "https://zoom.us/j/example"
            ),
            "streak", 12,
            "subscription", Map.of("used", 5, "total", 8, "expiresAt", "2026-06-01"),
            "courses", List.of(
                Map.of("id", 1, "language", "Французский B1", "teacher", "Софья Ф.", "nextDate", "СЕГОДНЯ · 18:30", "progress", 65),
                Map.of("id", 2, "language", "Английский A2+",  "teacher", "Татьяна К.", "nextDate", "ЧТ · 19:00",     "progress", 42)
            ),
            "homework", List.of(
                Map.of("id", 1, "title", "Эссе «Mes rêves» (200 слов)", "due", "до пт",   "status", "in_progress"),
                Map.of("id", 2, "title", "Listening · BBC News A2",      "due", "до ср",   "status", "not_started"),
                Map.of("id", 3, "title", "Лексика модуля 4 — Quizlet",   "due", "сегодня", "status", "done")
            ),
            "schedule", List.of(
                Map.of("date", "12", "dayLabel", "ВТ", "timeFrom", "18:30", "timeTo", "19:30", "subject", "Французский · Conditionnel", "teacher", "Софья Ф."),
                Map.of("date", "14", "dayLabel", "ЧТ", "timeFrom", "19:00", "timeTo", "20:00", "subject", "Английский · Past perfect",   "teacher", "Татьяна К."),
                Map.of("date", "16", "dayLabel", "СБ", "timeFrom", "12:00", "timeTo", "13:00", "subject", "Французский · Speaking club", "teacher", "Pierre (носитель)"),
                Map.of("date", "19", "dayLabel", "ВТ", "timeFrom", "18:30", "timeTo", "19:30", "subject", "Французский · Lecture",       "teacher", "Софья Ф.")
            )
        ));
    }

    @GetMapping("/teacher")
    public ResponseEntity<?> teacher() {
        return ResponseEntity.ok(Map.of(
            "schedule", List.of(
                Map.of("date", "12", "dayLabel", "ВТ", "timeFrom", "18:30", "timeTo", "19:30", "student", "Анна С.",    "subject", "FR · Conditionnel"),
                Map.of("date", "12", "dayLabel", "ВТ", "timeFrom", "20:00", "timeTo", "21:00", "student", "Лиза К.",    "subject", "FR · Subjonctif"),
                Map.of("date", "13", "dayLabel", "СР", "timeFrom", "19:00", "timeTo", "20:00", "student", "Михаил О.",  "subject", "FR · Lecture"),
                Map.of("date", "14", "dayLabel", "ЧТ", "timeFrom", "17:00", "timeTo", "18:00", "student", "Кирилл В.", "subject", "FR · Speaking")
            ),
            "attention", List.of(
                Map.of("who", "Анна С.",    "what", "Прислала эссе «Mes rêves»",   "timeAgo", "1ч назад", "type", "orange"),
                Map.of("who", "Лиза К.",    "what", "Просит перенести с пт на сб", "timeAgo", "3ч назад", "type", "purple"),
                Map.of("who", "Михаил О.",  "what", "Не пришёл на урок 09.05",     "timeAgo", "вчера",    "type", "red"),
                Map.of("who", "Кирилл В.", "what", "Новый ученик — план обучения", "timeAgo", "вчера",    "type", "green")
            ),
            "workload", Map.of(
                "days", List.of(
                    Map.of("label", "ПН", "pct", 30,  "today", false),
                    Map.of("label", "ВТ", "pct", 92,  "today", true),
                    Map.of("label", "СР", "pct", 60,  "today", false),
                    Map.of("label", "ЧТ", "pct", 75,  "today", false),
                    Map.of("label", "ПТ", "pct", 80,  "today", false),
                    Map.of("label", "СБ", "pct", 50,  "today", false),
                    Map.of("label", "ВС", "pct", 10,  "today", false)
                ),
                "totalHours", 23,
                "capacity", 28
            )
        ));
    }
}
