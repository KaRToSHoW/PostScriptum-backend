package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Подбор времени занятия («Подобрать время» при назначении урока).
 *
 * Кандидаты — начала занятий с шагом 30 минут (разовое занятие — на horizonDays дней вперёд;
 * регулярное — шаблон «день недели + время», проверяемый на каждой неделе серии).
 *
 * Жёсткие ограничения (кандидат отбрасывается):
 *   Ж1 — занятие целиком в окне доступности преподавателя (если окна не заданы — 08:00–21:00 по его времени);
 *   Ж2 — нет пересечений с занятиями преподавателя и каждого ученика с перерывом BUFFER_MIN;
 *   Ж3 — недельная нагрузка преподавателя не превышает capacity_hours;
 *   Ж4 — начало не раньше чем через час.
 * Ограничений по времени суток для учеников нет: часовой пояс каждого учитывается оценкой (М2).
 * Мягкие критерии (баллы, итог 0–100):
 *   М1 привычка — в это время ученик уже занимался (+25), то же время в другой день (+10);
 *   М2 удобство по местному времени ученика — 09:00–20:00 (+10), раньше 08:00 или позже 22:00 (−15),
 *      несовершеннолетнему позже 20:00 (−20);
 *   М3 компактность — занятие примыкает к другому занятию преподавателя (+15), отдельный рабочий день (−5);
 *   М4 равномерность — у ученика уже есть занятие в этот день (−30), накануне или на следующий день (−10);
 *   М5 близость — чем раньше, тем лучше (до −7);
 *   для регулярного: каждая неделя с конфликтом (−15), два дня в неделю через 2–3 дня (+10).
 * Время в запросе и ответе — в часовом поясе того, кто назначает (как и при создании занятия).
 */
@Service
@RequiredArgsConstructor
public class ScheduleSuggestService {

    private final JdbcTemplate jdbc;
    private final UserZoneService zones;

    static final int STEP_MIN = 30;
    static final int BUFFER_MIN = 10;
    static final LocalTime DEFAULT_FROM = LocalTime.of(8, 0);
    static final LocalTime DEFAULT_TO = LocalTime.of(21, 0);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");
    private static final String[] DOW = {"", "пн", "вт", "ср", "чт", "пт", "сб", "вс"};

    record Interval(Instant start, Instant end, String label) {
        boolean overlaps(Instant s, Instant e, int bufferMin) {
            return s.isBefore(end.plusSeconds(bufferMin * 60L)) && e.isAfter(start.minusSeconds(bufferMin * 60L));
        }
    }

    record Window(int weekday, LocalTime from, LocalTime to) {}

    static final class Student {
        long id; String name; ZoneId zone; boolean minor;
        List<Interval> busy = new ArrayList<>();
        /** привычные начала занятий: день недели и минута суток по времени ученика */
        List<int[]> habits = new ArrayList<>();
        String firstName() { return name == null ? "Ученик" : name.split(" ")[0]; }
    }

    /** Данные для оценки, загружаются один раз на запрос. */
    final class Ctx {
        ZoneId viewer; ZoneId teacherZone; int capacityMin;
        List<Window> windows = new ArrayList<>();
        List<Interval> teacherBusy = new ArrayList<>();
        List<Student> students = new ArrayList<>();
        Instant notBefore = Instant.now().plus(Duration.ofHours(1));
    }

    record Eval(boolean ok, String blocker, double score, List<String> reasons) {}

    // ─── публичные операции ────────────────────────────────────────────────

    /** Разовое занятие: лучшие варианты на ближайшие horizonDays дней (не больше двух в один день). */
    public Map<String, Object> suggestDates(long teacherId, List<Long> studentIds, int durationMin, int horizonDays, int limit) {
        LocalDate today = LocalDate.now(zones.current());
        Ctx ctx = load(teacherId, studentIds, today.minusDays(1), today.plusDays(horizonDays + 1));

        List<Map<String, Object>> found = new ArrayList<>();
        for (int d = 0; d < horizonDays; d++) {
            LocalDate day = today.plusDays(d);
            for (int slot = 0; slot < 24 * 60 / STEP_MIN; slot++) {   // все сутки: границы задают окна преподавателя
                LocalTime t = LocalTime.MIDNIGHT.plusMinutes((long) slot * STEP_MIN);
                ZonedDateTime start = day.atTime(t).atZone(ctx.viewer);
                Eval e = evaluate(ctx, start, durationMin);
                if (!e.ok()) continue;
                double score = e.score() - Math.min(7, d * 0.5);
                List<String> reasons = new ArrayList<>(e.reasons());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", day.toString());
                item.put("time", t.format(HM));
                item.put("label", DOW[day.getDayOfWeek().getValue()] + ", " + day.format(DM) + " в " + t.format(HM));
                item.put("score", clamp(score));
                item.put("reasons", reasons);
                item.put("localTimes", localTimes(ctx, start));
                found.add(item);
            }
        }
        found.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("score")).reversed()
            .thenComparing(m -> (String) m.get("date")).thenComparing(m -> (String) m.get("time")));

        // разнообразие: не больше двух вариантов в один день
        Map<String, Integer> perDay = new HashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> m : found) {
            String day = (String) m.get("date");
            if (perDay.merge(day, 1, Integer::sum) > 2) continue;
            result.add(m);
            if (result.size() >= limit) break;
        }
        return response(ctx, result, found.isEmpty() ? explainEmpty(ctx) : null);
    }

    /**
     * Регулярное занятие: шаблоны «дни недели + время» на weeks недель.
     * daysPerWeek 1 или 2 (для двух дней — одно время, дни через 2–3 дня).
     */
    public Map<String, Object> suggestWeekly(long teacherId, List<Long> studentIds, int durationMin, int weeks, int daysPerWeek, int limit) {
        LocalDate today = LocalDate.now(zones.current());
        Ctx ctx = load(teacherId, studentIds, today.minusDays(1), today.plusWeeks(weeks + 1));

        // оценка одного шаблона «день недели + время» по всем неделям серии
        record Weekly(int dow, LocalTime time, double score, int conflicts, List<String> conflictDates, List<String> reasons, Map<String, Object> local) {}
        List<Weekly> singles = new ArrayList<>();
        for (int dow = 1; dow <= 7; dow++) {
            LocalDate first = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.of(dow)));
            for (int slot = 0; slot < 24 * 60 / STEP_MIN; slot++) {   // все сутки: границы задают окна преподавателя
                LocalTime t = LocalTime.MIDNIGHT.plusMinutes((long) slot * STEP_MIN);
                double sum = 0; int ok = 0; int conflicts = 0;
                List<String> conflictDates = new ArrayList<>();
                List<String> reasons = null;
                Map<String, Object> local = null;
                boolean impossible = false;
                LocalDate date = first;
                // если сегодня это время уже прошло — серия начнётся со следующей недели
                if (date.equals(today) && !date.atTime(t).atZone(ctx.viewer).toInstant().isAfter(ctx.notBefore)) date = date.plusWeeks(1);
                for (int w = 0; w < weeks; w++, date = date.plusWeeks(1)) {
                    ZonedDateTime start = date.atTime(t).atZone(ctx.viewer);
                    Eval e = evaluate(ctx, start, durationMin);
                    if (!e.ok()) {
                        // вне окон / ночью у ученика — шаблон не подходит вовсе; занятость — конфликт этой недели
                        if (e.blocker() != null && e.blocker().startsWith("hard:")) { impossible = true; break; }
                        conflicts++;
                        conflictDates.add(date.format(DM));
                        continue;
                    }
                    sum += e.score(); ok++;
                    if (reasons == null) { reasons = e.reasons(); local = Map.of("items", localTimes(ctx, start)); }
                }
                if (impossible || ok == 0 || conflicts > Math.max(1, weeks / 4)) continue;
                double score = sum / ok - conflicts * 15;
                singles.add(new Weekly(dow, t, score, conflicts, conflictDates, reasons, local));
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        if (daysPerWeek <= 1) {
            singles.sort(Comparator.comparingDouble(Weekly::score).reversed());
            Map<Integer, Integer> perDow = new HashMap<>();
            for (Weekly w : singles) {
                if (perDow.merge(w.dow(), 1, Integer::sum) > 2) continue;
                result.add(weeklyItem(List.of(w.dow()), w.time(), w.score(), w.conflicts(), w.conflictDates(), w.reasons(), w.local()));
                if (result.size() >= limit) break;
            }
        } else {
            // пары дней с одинаковым временем; лучше всего — через 2–3 дня (пн+чт, вт+пт…)
            Map<LocalTime, List<Weekly>> byTime = singles.stream().collect(Collectors.groupingBy(Weekly::time));
            record Pair(Weekly a, Weekly b, double score) {}
            List<Pair> pairs = new ArrayList<>();
            for (List<Weekly> list : byTime.values()) {
                for (int i = 0; i < list.size(); i++) for (int j = i + 1; j < list.size(); j++) {
                    Weekly a = list.get(i), b = list.get(j);
                    int gap = Math.abs(a.dow() - b.dow());
                    int circular = Math.min(gap, 7 - gap);
                    if (circular < 2) continue;                    // подряд идущие дни не предлагаем
                    double s = (a.score() + b.score()) / 2 + (circular >= 2 && circular <= 3 ? 10 : 0);
                    pairs.add(a.dow() < b.dow() ? new Pair(a, b, s) : new Pair(b, a, s));
                }
            }
            pairs.sort(Comparator.comparingDouble(Pair::score).reversed());
            Set<String> used = new HashSet<>();
            for (Pair p : pairs) {
                String key = p.a().dow() + "-" + p.b().dow();
                if (!used.add(key)) continue;                     // одна пара дней — один лучший вариант времени
                List<String> reasons = new ArrayList<>(p.a().reasons());
                int gap = Math.min(Math.abs(p.a().dow() - p.b().dow()), 7 - Math.abs(p.a().dow() - p.b().dow()));
                if (gap <= 3) reasons.add(0, "Занятия равномерно: перерыв " + gap + " дн.");
                List<String> cd = new ArrayList<>(p.a().conflictDates()); cd.addAll(p.b().conflictDates());
                result.add(weeklyItem(List.of(p.a().dow(), p.b().dow()), p.a().time(), p.score(),
                    p.a().conflicts() + p.b().conflicts(), cd, reasons, p.a().local()));
                if (result.size() >= limit) break;
            }
        }
        return response(ctx, result, result.isEmpty() ? explainEmpty(ctx) : null);
    }

    // ─── оценка одного занятия ─────────────────────────────────────────────

    Eval evaluate(Ctx ctx, ZonedDateTime startViewer, int durationMin) {
        Instant s = startViewer.toInstant();
        Instant e = s.plusSeconds(durationMin * 60L);
        if (!s.isAfter(ctx.notBefore)) return new Eval(false, "hard:past", 0, List.of());

        // Ж1 — окна доступности преподавателя (по его времени)
        ZonedDateTime tStart = s.atZone(ctx.teacherZone);
        ZonedDateTime tEnd = e.atZone(ctx.teacherZone);
        if (!tStart.toLocalDate().equals(tEnd.toLocalDate()) && !tEnd.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return new Eval(false, "hard:midnight", 0, List.of());
        }
        LocalTime tf = tStart.toLocalTime();
        LocalTime tt = tEnd.toLocalTime().equals(LocalTime.MIDNIGHT) ? LocalTime.MAX : tEnd.toLocalTime();
        boolean inWindow;
        if (ctx.windows.isEmpty()) {
            inWindow = !tf.isBefore(DEFAULT_FROM) && !tt.isAfter(DEFAULT_TO);
        } else {
            int wd = tStart.getDayOfWeek().getValue();
            inWindow = ctx.windows.stream().anyMatch(w -> w.weekday() == wd && !tf.isBefore(w.from()) && !tt.isAfter(w.to()));
        }
        if (!inWindow) return new Eval(false, "hard:window", 0, List.of());

        // Ж2 — занятость преподавателя и учеников
        for (Interval b : ctx.teacherBusy) if (b.overlaps(s, e, BUFFER_MIN)) return new Eval(false, "busy:teacher", 0, List.of());
        for (Student st : ctx.students) for (Interval b : st.busy) if (b.overlaps(s, e, BUFFER_MIN)) return new Eval(false, "busy:" + st.id, 0, List.of());

        // Ж3 — недельная нагрузка преподавателя
        if (ctx.capacityMin > 0) {
            int week = tStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            int year = tStart.get(IsoFields.WEEK_BASED_YEAR);
            long minutes = ctx.teacherBusy.stream()
                .filter(b -> { ZonedDateTime z = b.start().atZone(ctx.teacherZone); return z.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == week && z.get(IsoFields.WEEK_BASED_YEAR) == year; })
                .mapToLong(b -> Duration.between(b.start(), b.end()).toMinutes()).sum();
            if (minutes + durationMin > ctx.capacityMin) return new Eval(false, "busy:capacity", 0, List.of());
        }

        // ── мягкие критерии ──
        double score = 50;
        List<String> reasons = new ArrayList<>();

        // М1 привычка и М2 удобство — по каждому ученику, среднее
        double habit = 0, comfort = 0;
        for (Student st : ctx.students) {
            ZonedDateTime ls = s.atZone(st.zone);
            int minute = ls.getHour() * 60 + ls.getMinute();
            int dow = ls.getDayOfWeek().getValue();
            boolean sameSlot = st.habits.stream().anyMatch(h -> h[0] == dow && Math.abs(h[1] - minute) <= 30);
            boolean sameTime = !sameSlot && st.habits.stream().anyMatch(h -> Math.abs(h[1] - minute) <= 30);
            if (sameSlot) { habit += 25; reasons.add("В это время " + st.firstName() + " уже занимается — привычное время"); }
            else if (sameTime) { habit += 10; reasons.add("Привычное для " + st.firstName() + " время, другой день"); }

            // Часовые поясы учитываются оценкой, а не запретом: ограничений по времени суток у школы нет
            ZonedDateTime le = ls.plusMinutes(durationMin);
            LocalTime lt = ls.toLocalTime(), lend = le.toLocalTime();
            boolean nextDay = !le.toLocalDate().equals(ls.toLocalDate()) && !lend.equals(LocalTime.MIDNIGHT);
            boolean lateEnd = nextDay || lend.isAfter(LocalTime.of(22, 0)) || lend.equals(LocalTime.MIDNIGHT);
            if (!lt.isBefore(LocalTime.of(9, 0)) && !nextDay && !lend.isAfter(LocalTime.of(20, 0))) comfort += 10;
            if (lt.isBefore(LocalTime.of(8, 0)) || lateEnd) {
                comfort -= 15;
                reasons.add("У " + st.firstName() + " это " + lt.format(HM) + " по местному времени — " + (lateEnd ? "поздно" : "рано"));
            }
            if (st.minor && (nextDay || lend.isAfter(LocalTime.of(20, 0)))) { comfort -= 20; reasons.add("Поздно для ученика младше 18 лет (" + st.firstName() + ")"); }
        }
        int n = Math.max(1, ctx.students.size());
        score += habit / n + comfort / n;

        // М3 компактность дня преподавателя
        LocalDate tDay = tStart.toLocalDate();
        List<Interval> sameDay = ctx.teacherBusy.stream().filter(b -> b.start().atZone(ctx.teacherZone).toLocalDate().equals(tDay)).toList();
        if (sameDay.isEmpty()) {
            score -= 5;
        } else {
            Optional<Interval> adjacent = sameDay.stream().filter(b ->
                Math.abs(Duration.between(b.end(), s).toMinutes()) <= BUFFER_MIN + 30
                    || Math.abs(Duration.between(e, b.start()).toMinutes()) <= BUFFER_MIN + 30).findFirst();
            if (adjacent.isPresent()) {
                score += 15;
                reasons.add("Без «окна» у преподавателя: рядом урок" + (adjacent.get().label() != null ? " (" + adjacent.get().label() + ")" : ""));
            }
        }

        // М4 равномерность для каждого ученика
        for (Student st : ctx.students) {
            LocalDate sd = s.atZone(st.zone).toLocalDate();
            boolean sameDayLesson = st.busy.stream().anyMatch(b -> b.start().atZone(st.zone).toLocalDate().equals(sd));
            boolean nearDay = st.busy.stream().anyMatch(b -> {
                long diff = Math.abs(b.start().atZone(st.zone).toLocalDate().toEpochDay() - sd.toEpochDay());
                return diff == 1;
            });
            if (sameDayLesson) { score -= 30; reasons.add("У " + st.firstName() + " в этот день уже есть занятие"); }
            else if (nearDay) score -= 10;
        }

        // общая причина — последней, чтобы на карточке первыми были отличающие вариант
        reasons.add(ctx.windows.isEmpty() ? "Рабочее время преподавателя (08:00–21:00)" : "В окне доступности преподавателя");
        return new Eval(true, null, score, reasons);
    }

    // ─── загрузка данных ───────────────────────────────────────────────────

    Ctx load(long teacherId, List<Long> studentIds, LocalDate from, LocalDate to) {
        Ctx ctx = new Ctx();
        ctx.viewer = zones.current();
        ctx.teacherZone = zones.of(teacherId);
        Integer cap = null;
        try {
            cap = jdbc.queryForObject("SELECT capacity_hours FROM teacher_profiles WHERE user_id = ?", Integer.class, teacherId);
        } catch (Exception ignored) { }
        ctx.capacityMin = cap != null ? cap * 60 : 0;

        for (Map<String, Object> w : jdbc.queryForList(
                "SELECT weekday, start_time, end_time FROM teacher_availability WHERE teacher_id = ? AND weekday IS NOT NULL", teacherId)) {
            ctx.windows.add(new Window(((Number) w.get("weekday")).intValue(),
                ((Time) w.get("start_time")).toLocalTime(), ((Time) w.get("end_time")).toLocalTime()));
        }

        Timestamp fromTs = Timestamp.from(from.atStartOfDay(ctx.viewer).toInstant());
        Timestamp toTs = Timestamp.from(to.atStartOfDay(ctx.viewer).toInstant());

        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT l.scheduled_at, COALESCE(l.duration_min, 60) AS dur,
                       (SELECT STRING_AGG(u.name, ', ') FROM lesson_students ls JOIN users u ON u.id = ls.student_id WHERE ls.lesson_id = l.id) AS who
                FROM lessons l
                WHERE l.teacher_id = ? AND l.status IN ('PLANNED','IN_PROGRESS','DONE')
                  AND l.scheduled_at >= ? AND l.scheduled_at < ?
                """, teacherId, fromTs, toTs)) {
            ctx.teacherBusy.add(interval(r, (String) r.get("who")));
        }

        for (Long sid : studentIds) {
            Map<String, Object> u;
            try {
                u = jdbc.queryForMap("""
                    SELECT u.id, u.name, sp.birth_date FROM users u
                    LEFT JOIN student_profiles sp ON sp.user_id = u.id WHERE u.id = ?
                    """, sid);
            } catch (Exception e) { continue; }
            Student st = new Student();
            st.id = sid;
            st.name = (String) u.get("name");
            st.zone = zones.of(sid);
            if (u.get("birth_date") instanceof Date bd) {
                st.minor = Period.between(bd.toLocalDate(), LocalDate.now()).getYears() < 18;
            }
            for (Map<String, Object> r : jdbc.queryForList("""
                    SELECT l.scheduled_at, COALESCE(l.duration_min, 60) AS dur
                    FROM lesson_students ls JOIN lessons l ON l.id = ls.lesson_id
                    WHERE ls.student_id = ? AND l.status IN ('PLANNED','IN_PROGRESS','DONE')
                      AND l.scheduled_at >= ? AND l.scheduled_at < ?
                    """, sid, fromTs, toTs)) {
                st.busy.add(interval(r, null));
            }
            for (Timestamp ts : jdbc.queryForList("""
                    SELECT l.scheduled_at FROM lesson_students ls JOIN lessons l ON l.id = ls.lesson_id
                    WHERE ls.student_id = ? AND l.status <> 'CANCELLED'
                      AND l.scheduled_at > NOW() - INTERVAL '90 days' AND l.scheduled_at < NOW() + INTERVAL '30 days'
                    """, Timestamp.class, sid)) {
                ZonedDateTime z = ts.toInstant().atZone(st.zone);
                st.habits.add(new int[]{z.getDayOfWeek().getValue(), z.getHour() * 60 + z.getMinute()});
            }
            ctx.students.add(st);
        }
        return ctx;
    }

    private static Interval interval(Map<String, Object> r, String label) {
        Instant s = ((Timestamp) r.get("scheduled_at")).toInstant();
        int dur = ((Number) r.get("dur")).intValue();
        return new Interval(s, s.plusSeconds(dur * 60L), label);
    }

    // ─── ответ ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> localTimes(Ctx ctx, ZonedDateTime start) {
        List<Map<String, Object>> list = new ArrayList<>();
        Set<ZoneId> shown = new HashSet<>();
        shown.add(ctx.viewer);
        for (Student st : ctx.students) {
            if (st.zone.equals(ctx.viewer)) continue;
            list.add(Map.of("name", st.firstName(), "time", start.withZoneSameInstant(st.zone).format(HM), "zone", st.zone.getId()));
            shown.add(st.zone);
        }
        if (!shown.contains(ctx.teacherZone)) {
            list.add(Map.of("name", "преподаватель", "time", start.withZoneSameInstant(ctx.teacherZone).format(HM), "zone", ctx.teacherZone.getId()));
        }
        return list;
    }

    private Map<String, Object> weeklyItem(List<Integer> days, LocalTime time, double score, int conflicts,
                                           List<String> conflictDates, List<String> reasons, Map<String, Object> local) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", days);
        m.put("time", time.format(HM));
        m.put("label", days.stream().map(d -> DOW[d]).collect(Collectors.joining(" и ")) + " в " + time.format(HM));
        m.put("score", clamp(score));
        List<String> r = new ArrayList<>(reasons == null ? List.of() : reasons);
        r.add(0, conflicts == 0 ? "Свободно на всех неделях серии" : "Занято на " + conflicts + " нед. (" + String.join(", ", conflictDates) + ") — эти даты будут пропущены");
        m.put("reasons", r);
        m.put("conflicts", conflicts);
        m.put("localTimes", local == null ? List.of() : local.get("items"));
        return m;
    }

    private Map<String, Object> response(Ctx ctx, List<Map<String, Object>> items, String emptyReason) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", items);
        res.put("windowsDefined", !ctx.windows.isEmpty());
        res.put("timezone", ctx.viewer.getId());
        if (emptyReason != null) res.put("emptyReason", emptyReason);
        return res;
    }

    private String explainEmpty(Ctx ctx) {
        return ctx.windows.isEmpty()
            ? "Нет свободного времени с 08:00 до 21:00 у преподавателя, удобного всем ученикам"
            : "В окнах доступности преподавателя нет времени, свободного у всех участников. Расширьте окна или уменьшите длительность";
    }

    private static int clamp(double v) {
        return (int) Math.round(Math.max(0, Math.min(100, v)));
    }
}
