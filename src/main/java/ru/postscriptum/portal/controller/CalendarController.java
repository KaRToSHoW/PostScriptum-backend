package ru.postscriptum.portal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    // TODO: заменить на реальные данные из БД по запрошенному месяцу

    @GetMapping
    public ResponseEntity<?> student(
            @RequestParam(defaultValue = "2026") int year,
            @RequestParam(defaultValue = "5")    int month) {

        return ResponseEntity.ok(Map.of(
            "year",  year,
            "month", month,
            "events", Map.of(
                "1",  List.of(Map.of("time", "10:00", "title", "FR · Анна",       "lang", "fr", "status", "done")),
                "4",  List.of(Map.of("time", "18:30", "title", "FR · Анна",       "lang", "fr", "status", "done"),
                               Map.of("time", "19:00", "title", "EN · Михаил",     "lang", "en", "status", "done")),
                "12", List.of(Map.of("time", "10:00", "title", "FR · Анна",       "lang", "fr", "status", "today"),
                               Map.of("time", "15:00", "title", "FR · пара",       "lang", "fr", "status", "now"),
                               Map.of("time", "18:30", "title", "FR · Lecture",    "lang", "fr", "status", "today")),
                "18", List.of(Map.of("time", "18:30", "title", "FR · Анна",       "lang", "fr", "status", "planned")),
                "19", List.of(Map.of("time", "10:00", "title", "DE · Денис",      "lang", "de", "status", "planned"),
                               Map.of("time", "18:30", "title", "FR · Анна",       "lang", "fr", "status", "planned")),
                "25", List.of(Map.of("time", "10:00", "title", "FR · Анна",       "lang", "fr", "status", "planned")),
                "26", List.of(Map.of("time", "18:30", "title", "FR · группа",     "lang", "fr", "status", "planned"))
            )
        ));
    }

    @GetMapping("/admin")
    public ResponseEntity<?> admin(
            @RequestParam(defaultValue = "2026") int year,
            @RequestParam(defaultValue = "5")    int month) {

        return ResponseEntity.ok(Map.of(
            "year",  year,
            "month", month,
            "rooms", List.of(
                Map.of("id", 1, "name", "Zoom — основной"),
                Map.of("id", 2, "name", "Zoom — зал 2"),
                Map.of("id", 3, "name", "Офис · каб. 1")
            ),
            "slots", List.of(
                Map.of("day", 12, "timeFrom", "10:00", "timeTo", "11:00",
                       "teacher", "Софья Ф.",   "student", "Анна С.",    "lang", "fr", "room", 1, "status", "today"),
                Map.of("day", 12, "timeFrom", "15:00", "timeTo", "16:00",
                       "teacher", "Pierre B.",   "student", "группа FR",  "lang", "fr", "room", 2, "status", "now"),
                Map.of("day", 12, "timeFrom", "18:30", "timeTo", "19:30",
                       "teacher", "Татьяна К.", "student", "Михаил О.",  "lang", "en", "room", 1, "status", "today"),
                Map.of("day", 18, "timeFrom", "18:30", "timeTo", "19:30",
                       "teacher", "Софья Ф.",   "student", "Анна С.",    "lang", "fr", "room", 1, "status", "planned"),
                Map.of("day", 19, "timeFrom", "10:00", "timeTo", "11:00",
                       "teacher", "Иван Ш.",    "student", "Денис Н.",   "lang", "de", "room", 3, "status", "planned")
            )
        ));
    }
}
