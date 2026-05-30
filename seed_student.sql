-- =============================================================
--  P.S. PORTAL — Демо-данные для ученика
--  Ученик: anna@test.ru / student123
--  Запустить после schema.sql
-- =============================================================

-- ─────────────────────────────────────────
--  СПРАВОЧНИКИ
-- ─────────────────────────────────────────

INSERT INTO languages (code, name_ru, flag_emoji) VALUES
  ('fr', 'Французский', '🇫🇷'),
  ('en', 'Английский',  '🇬🇧'),
  ('de', 'Немецкий',    '🇩🇪'),
  ('es', 'Испанский',   '🇪🇸'),
  ('it', 'Итальянский', '🇮🇹')
ON CONFLICT (code) DO NOTHING;

INSERT INTO levels (code, label) VALUES
  ('A0', 'Нулевой'),
  ('A1', 'Начальный'),
  ('A2', 'Элементарный'),
  ('B1', 'Средний'),
  ('B2', 'Выше среднего'),
  ('C1', 'Продвинутый'),
  ('C2', 'Профессиональный')
ON CONFLICT (code) DO NOTHING;

INSERT INTO rooms (name, capacity, zoom_url) VALUES
  ('Zoom — основной',  1,  'https://zoom.us/j/111000111'),
  ('Zoom — зал 2',     8,  'https://zoom.us/j/222000222'),
  ('Speaking Room FR', 12, 'https://zoom.us/j/333000333')
ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────
--  ПОЛЬЗОВАТЕЛИ
-- ─────────────────────────────────────────

INSERT INTO users (email, password_hash, name, initials, role, phone, timezone, locale, is_active, created_at, updated_at) VALUES
  ('anna@test.ru',    '$2a$10$2z6lV0EWzUFchbmI/T6UuuVvHzc/LeNVA3iRPmyqGt/oVV8RGpi6m', 'Анна Соколова',      'АС', 'STUDENT', '+7 905 123-45-67', 'Europe/Moscow',       'ru', true, NOW(), NOW()),
  ('sofia@test.ru',   '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'Софья Фролова',      'СФ', 'TEACHER', '+7 916 234-56-78', 'Europe/Moscow',       'ru', true, NOW(), NOW()),
  ('tatyana@test.ru', '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'Татьяна Кравченко',  'ТК', 'TEACHER', '+7 926 345-67-89', 'Europe/Moscow',       'ru', true, NOW(), NOW()),
  ('pierre@test.ru',  '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'Pierre Bouchard',    'PB', 'TEACHER', NULL,                'Europe/Paris',        'fr', true, NOW(), NOW()),
  ('laura@test.ru',   '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'Лаура Мартин',       'ЛМ', 'TEACHER', NULL,                'Europe/Madrid',       'ru', true, NOW(), NOW()),
  ('ivan@test.ru',    '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'Иван Шульц',         'ИШ', 'TEACHER', NULL,                'Europe/Moscow',       'ru', true, NOW(), NOW()),
  ('admin@test.ru',   '$2a$10$jAb4PISNF75./HLDlKtS3evj5Y54bfHPe56dahSTMO6vM38miI6/W',  'Михаил Фролов',      'МФ', 'ADMIN',   '+7 495 000-00-00', 'Europe/Moscow',       'ru', true, NOW(), NOW())
ON CONFLICT (email) DO NOTHING;

-- ─────────────────────────────────────────
--  ПРОФИЛИ УЧЕНИКОВ
-- ─────────────────────────────────────────

INSERT INTO student_profiles (user_id, current_level_id, streak_days, streak_last_date)
SELECT u.id, l.id, 12, '2026-05-30'
FROM users u, levels l
WHERE u.email = 'anna@test.ru' AND l.code = 'B1'
ON CONFLICT (user_id) DO NOTHING;

-- ─────────────────────────────────────────
--  ПРОФИЛИ ПРЕПОДАВАТЕЛЕЙ
-- ─────────────────────────────────────────

INSERT INTO teacher_profiles (user_id, bio, capacity_hours, is_native, rating, workload_chip)
SELECT id,
  'Специализируюсь на разговорном французском и подготовке к DELF/DALF. Люблю строить уроки вокруг реальных ситуаций — кафе, путешествия, переговоры.',
  28, false, 4.9, 'orange'
FROM users WHERE email = 'sofia@test.ru'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO teacher_profiles (user_id, bio, capacity_hours, is_native, rating, workload_chip)
SELECT id,
  'Фокус на деловом английском и подготовке к IELTS/TOEFL. Работаю со взрослыми и подростками с уровня A2.',
  24, false, 4.8, 'orange'
FROM users WHERE email = 'tatyana@test.ru'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO teacher_profiles (user_id, bio, capacity_hours, is_native, rating, workload_chip)
SELECT id,
  'Родился и вырос в Лионе. Веду разговорные клубы и помогаю поставить настоящий французский акцент. Уроки живые и неформальные.',
  20, true, 5.0, 'orange'
FROM users WHERE email = 'pierre@test.ru'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO teacher_profiles (user_id, bio, capacity_hours, is_native, rating, workload_chip)
SELECT id, 'Специализируюсь на испанском — от нуля до уверенного разговора. Использую метод погружения и много практики.',
  22, false, 4.7, 'orange'
FROM users WHERE email = 'laura@test.ru'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO teacher_profiles (user_id, bio, capacity_hours, is_native, rating, workload_chip)
SELECT id, 'Веду немецкий с нуля до B2. Объясняю грамматику простым языком, много практики через диалоги.',
  20, false, 4.6, 'orange'
FROM users WHERE email = 'ivan@test.ru'
ON CONFLICT (user_id) DO NOTHING;

-- ─────────────────────────────────────────
--  ЯЗЫКИ ПРЕПОДАВАТЕЛЕЙ
-- ─────────────────────────────────────────

INSERT INTO teacher_languages (teacher_id, language_id, level_id, is_primary)
SELECT u.id, l.id, lv.id, true
FROM users u, languages l, levels lv
WHERE u.email = 'sofia@test.ru' AND l.code = 'fr' AND lv.code = 'C2'
ON CONFLICT DO NOTHING;

INSERT INTO teacher_languages (teacher_id, language_id, level_id, is_primary)
SELECT u.id, l.id, lv.id, false
FROM users u, languages l, levels lv
WHERE u.email = 'sofia@test.ru' AND l.code = 'en' AND lv.code = 'B1'
ON CONFLICT DO NOTHING;

INSERT INTO teacher_languages (teacher_id, language_id, level_id, is_primary)
SELECT u.id, l.id, lv.id, true
FROM users u, languages l, levels lv
WHERE u.email = 'tatyana@test.ru' AND l.code = 'en' AND lv.code = 'C1'
ON CONFLICT DO NOTHING;

INSERT INTO teacher_languages (teacher_id, language_id, level_id, is_primary)
SELECT u.id, l.id, lv.id, true
FROM users u, languages l, levels lv
WHERE u.email = 'pierre@test.ru' AND l.code = 'fr' AND lv.code = 'C2'
ON CONFLICT DO NOTHING;

INSERT INTO teacher_languages (teacher_id, language_id, level_id, is_primary)
SELECT u.id, l.id, lv.id, true
FROM users u, languages l, levels lv
WHERE u.email = 'laura@test.ru' AND l.code = 'es' AND lv.code = 'C2'
ON CONFLICT DO NOTHING;

INSERT INTO teacher_languages (teacher_id, language_id, level_id, is_primary)
SELECT u.id, l.id, lv.id, true
FROM users u, languages l, levels lv
WHERE u.email = 'ivan@test.ru' AND l.code = 'de' AND lv.code = 'C1'
ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────
--  КУРСЫ
-- ─────────────────────────────────────────

INSERT INTO courses (language_id, level_id, title, description, total_lessons, is_active)
SELECT l.id, lv.id,
  'Французский B1 — Разговорный', 'Курс на уверенный разговорный французский. DELF B1.',
  32, true
FROM languages l, levels lv WHERE l.code = 'fr' AND lv.code = 'B1';

INSERT INTO courses (language_id, level_id, title, description, total_lessons, is_active)
SELECT l.id, lv.id,
  'Английский A2+ — General English', 'Переход с A2 на B1. Деловая и бытовая лексика.',
  24, true
FROM languages l, levels lv WHERE l.code = 'en' AND lv.code = 'A2';

-- ─────────────────────────────────────────
--  ЗАЧИСЛЕНИЯ
-- ─────────────────────────────────────────

INSERT INTO enrollments (student_id, course_id, teacher_id, start_date, progress_pct, lessons_done, lessons_total, is_active)
SELECT s.id, c.id, t.id, '2026-01-15', 72, 8, 32, true
FROM users s, courses c, users t
WHERE s.email = 'anna@test.ru'
  AND c.title = 'Французский B1 — Разговорный'
  AND t.email = 'sofia@test.ru';

INSERT INTO enrollments (student_id, course_id, teacher_id, start_date, progress_pct, lessons_done, lessons_total, is_active)
SELECT s.id, c.id, t.id, '2026-02-01', 41, 5, 24, true
FROM users s, courses c, users t
WHERE s.email = 'anna@test.ru'
  AND c.title = 'Английский A2+ — General English'
  AND t.email = 'tatyana@test.ru';

-- ─────────────────────────────────────────
--  АБОНЕМЕНТЫ
-- ─────────────────────────────────────────

INSERT INTO subscription_plans (language_id, name, lesson_count, price, validity_days)
SELECT l.id, 'Французский · 8 уроков', 8, 12800.00, 30
FROM languages l WHERE l.code = 'fr';

INSERT INTO subscription_plans (language_id, name, lesson_count, price, validity_days)
SELECT l.id, 'Английский · 8 уроков', 8, 11200.00, 30
FROM languages l WHERE l.code = 'en';

INSERT INTO subscription_plans (name, lesson_count, price, validity_days)
VALUES ('Универсальный · 4 урока', 4, 7200.00, 30);

INSERT INTO subscriptions (student_id, plan_id, start_date, end_date, lessons_used, lessons_total, status)
SELECT u.id, p.id, '2026-05-05', '2026-06-04', 5, 8, 'ACTIVE'
FROM users u, subscription_plans p
WHERE u.email = 'anna@test.ru' AND p.name = 'Французский · 8 уроков';

INSERT INTO subscriptions (student_id, plan_id, start_date, end_date, lessons_used, lessons_total, status)
SELECT u.id, p.id, '2026-04-01', '2026-04-30', 8, 8, 'EXPIRED'
FROM users u, subscription_plans p
WHERE u.email = 'anna@test.ru' AND p.name = 'Французский · 8 уроков';

INSERT INTO payments (student_id, subscription_id, amount, method, status, paid_at)
SELECT u.id, s.id, 12800.00, 'CARD', 'PAID', '2026-05-05 10:00:00+03'
FROM users u
JOIN subscriptions s ON s.student_id = u.id
JOIN subscription_plans p ON p.id = s.plan_id
WHERE u.email = 'anna@test.ru' AND p.name = 'Французский · 8 уроков'
  AND s.status = 'ACTIVE';

INSERT INTO payments (student_id, subscription_id, amount, method, status, paid_at)
SELECT u.id, s.id, 12800.00, 'SBP', 'PAID', '2026-04-01 09:30:00+03'
FROM users u
JOIN subscriptions s ON s.student_id = u.id
JOIN subscription_plans p ON p.id = s.plan_id
WHERE u.email = 'anna@test.ru' AND p.name = 'Французский · 8 уроков'
  AND s.status = 'EXPIRED';

-- ─────────────────────────────────────────
--  УРОКИ — ФРАНЦУЗСКИЙ (прошедшие)
-- ─────────────────────────────────────────

WITH ids AS (
  SELECT
    (SELECT id FROM users WHERE email = 'anna@test.ru')   AS student,
    (SELECT id FROM users WHERE email = 'sofia@test.ru')  AS teacher,
    (SELECT id FROM languages WHERE code = 'fr')          AS lang,
    (SELECT e.id FROM enrollments e
       JOIN users s ON s.id = e.student_id
       JOIN users t ON t.id = e.teacher_id
       WHERE s.email = 'anna@test.ru' AND t.email = 'sofia@test.ru') AS enroll,
    (SELECT id FROM rooms WHERE name = 'Zoom — основной') AS room
)
INSERT INTO lessons (enrollment_id, teacher_id, language_id, room_id, format, topic, scheduled_at, duration_min, zoom_url, status, original_date, created_at)
SELECT enroll, teacher, lang, room, 'INDIVIDUAL',
       topic, ts::timestamptz, 60,
       'https://zoom.us/j/111000111', st::lesson_status, ts::timestamptz, NOW()
FROM ids,
(VALUES
  ('Présent de l''indicatif — повторение',               '2026-05-05 18:30:00+03', 'DONE'),
  ('Passé composé vs Imparfait',                         '2026-05-08 18:30:00+03', 'DONE'),
  ('Conditionnel présent — мечтаем по-французски',       '2026-05-12 18:30:00+03', 'DONE'),
  ('Conditionnel passé — сослагательное прошедшее',      '2026-05-15 18:30:00+03', 'DONE'),
  ('Subjonctif présent — введение',                      '2026-05-19 18:30:00+03', 'DONE'),
  ('Vocabulaire: voyages et transports',                 '2026-05-22 18:30:00+03', 'DONE'),
  ('Dialogue: à la réception de l''hôtel',               '2026-05-26 18:30:00+03', 'DONE'),
  ('Subjonctif: verbes irréguliers',                     '2026-05-29 18:30:00+03', 'MISSED')
) AS v(topic, ts, st);

-- Уроки французского (предстоящие)
WITH ids AS (
  SELECT
    (SELECT id FROM users WHERE email = 'sofia@test.ru')  AS teacher,
    (SELECT id FROM languages WHERE code = 'fr')          AS lang,
    (SELECT e.id FROM enrollments e
       JOIN users s ON s.id = e.student_id
       JOIN users t ON t.id = e.teacher_id
       WHERE s.email = 'anna@test.ru' AND t.email = 'sofia@test.ru') AS enroll,
    (SELECT id FROM rooms WHERE name = 'Zoom — основной') AS room
)
INSERT INTO lessons (enrollment_id, teacher_id, language_id, room_id, format, topic, scheduled_at, duration_min, zoom_url, status, original_date, created_at)
SELECT enroll, teacher, lang, room, 'INDIVIDUAL',
       topic, ts::timestamptz, 60, 'https://zoom.us/j/111000111', 'PLANNED', ts::timestamptz, NOW()
FROM ids,
(VALUES
  ('Subjonctif: expressions courantes',  '2026-06-02 18:30:00+03'),
  ('Révision: Subjonctif complet',       '2026-06-05 18:30:00+03'),
  ('Le discours indirect',               '2026-06-09 18:30:00+03'),
  ('Vocabulaire: au restaurant',         '2026-06-12 18:30:00+03')
) AS v(topic, ts);

-- Добавляем student_id в lesson_students для всех уроков Анны
INSERT INTO lesson_students (lesson_id, student_id, attended)
SELECT l.id, u.id,
  CASE l.status WHEN 'DONE' THEN true WHEN 'MISSED' THEN false ELSE NULL END
FROM lessons l, users u
WHERE u.email = 'anna@test.ru'
  AND l.teacher_id = (SELECT id FROM users WHERE email = 'sofia@test.ru')
ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────
--  УРОКИ — АНГЛИЙСКИЙ
-- ─────────────────────────────────────────

WITH ids AS (
  SELECT
    (SELECT id FROM users WHERE email = 'anna@test.ru')    AS student,
    (SELECT id FROM users WHERE email = 'tatyana@test.ru') AS teacher,
    (SELECT id FROM languages WHERE code = 'en')           AS lang,
    (SELECT e.id FROM enrollments e
       JOIN users s ON s.id = e.student_id
       JOIN users t ON t.id = e.teacher_id
       WHERE s.email = 'anna@test.ru' AND t.email = 'tatyana@test.ru') AS enroll,
    (SELECT id FROM rooms WHERE name = 'Zoom — основной') AS room
)
INSERT INTO lessons (enrollment_id, teacher_id, language_id, room_id, format, topic, scheduled_at, duration_min, zoom_url, status, original_date, created_at)
SELECT enroll, teacher, lang, room, 'INDIVIDUAL',
       topic, ts::timestamptz, 60, 'https://zoom.us/j/111000111', st::lesson_status, ts::timestamptz, NOW()
FROM ids,
(VALUES
  ('Present Perfect vs Past Simple',  '2026-05-07 19:00:00+03', 'DONE'),
  ('Past Perfect — usage & practice', '2026-05-14 19:00:00+03', 'DONE'),
  ('Future forms: will, going to',    '2026-05-21 19:00:00+03', 'DONE'),
  ('Modal verbs: must, should, can',  '2026-05-28 19:00:00+03', 'PLANNED'),
  ('Vocabulary: business emails',     '2026-06-04 19:00:00+03', 'PLANNED'),
  ('IELTS Reading practice',          '2026-06-11 19:00:00+03', 'PLANNED')
) AS v(topic, ts, st);

INSERT INTO lesson_students (lesson_id, student_id, attended)
SELECT l.id, u.id,
  CASE l.status WHEN 'DONE' THEN true WHEN 'MISSED' THEN false ELSE NULL END
FROM lessons l, users u
WHERE u.email = 'anna@test.ru'
  AND l.teacher_id = (SELECT id FROM users WHERE email = 'tatyana@test.ru')
ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────
--  SPEAKING CLUB (Pierre)
-- ─────────────────────────────────────────

INSERT INTO speaking_clubs (language_id, teacher_id, title, topic, scheduled_at, duration_min, max_students, zoom_url, room_id, status)
SELECT l.id, u.id,
  'French Speaking Club A2-B1',
  'Voyages et aventures — делимся историями',
  '2026-06-07 12:00:00+03', 90, 8,
  'https://zoom.us/j/333000333',
  (SELECT id FROM rooms WHERE name = 'Speaking Room FR'),
  'PLANNED'
FROM languages l, users u
WHERE l.code = 'fr' AND u.email = 'pierre@test.ru';

INSERT INTO speaking_club_registrations (club_id, student_id, registered_at)
SELECT sc.id, u.id, NOW()
FROM speaking_clubs sc, users u
WHERE u.email = 'anna@test.ru'
ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────
--  ДОМАШНИЕ ЗАДАНИЯ
-- ─────────────────────────────────────────

-- Домашние задания по французскому
INSERT INTO homework (lesson_id, teacher_id, student_id, title, description, due_at, status, created_at)
SELECT
  (SELECT id FROM lessons WHERE teacher_id = (SELECT id FROM users WHERE email = 'sofia@test.ru')
     AND topic LIKE '%Conditionnel présent%' LIMIT 1),
  (SELECT id FROM users WHERE email = 'sofia@test.ru'),
  (SELECT id FROM users WHERE email = 'anna@test.ru'),
  'Эссе «Mes rêves» (200 слов)',
  'Напишите эссе о ваших мечтах на французском. Используйте Conditionnel présent.',
  '2026-06-01 23:59:00+03'::timestamptz, 'ASSIGNED'::homework_status, NOW();

INSERT INTO homework (lesson_id, teacher_id, student_id, title, description, due_at, status, created_at)
SELECT
  (SELECT id FROM lessons WHERE teacher_id = (SELECT id FROM users WHERE email = 'sofia@test.ru')
     AND topic LIKE '%Vocabulaire%' LIMIT 1),
  (SELECT id FROM users WHERE email = 'sofia@test.ru'),
  (SELECT id FROM users WHERE email = 'anna@test.ru'),
  'Лексика модуля 7 — Quizlet',
  'Выучите 30 слов по теме «Путешествия» в Quizlet. Достичь 100% на тесте.',
  '2026-05-31 23:59:00+03'::timestamptz, 'SUBMITTED'::homework_status, NOW();

INSERT INTO homework (lesson_id, teacher_id, student_id, title, description, due_at, status, created_at)
SELECT
  (SELECT id FROM lessons WHERE teacher_id = (SELECT id FROM users WHERE email = 'sofia@test.ru')
     AND topic LIKE '%gare%' LIMIT 1),
  (SELECT id FROM users WHERE email = 'sofia@test.ru'),
  (SELECT id FROM users WHERE email = 'anna@test.ru'),
  'Аудирование «À la gare» + транскрипт',
  'Прослушайте диалог 3 раза, напишите транскрипт и переведите.',
  '2026-05-22 23:59:00+03'::timestamptz, 'REVIEWED'::homework_status, NOW();

-- Домашка по английскому
INSERT INTO homework (lesson_id, teacher_id, student_id, title, description, due_at, status, created_at)
SELECT
  (SELECT id FROM lessons WHERE teacher_id = (SELECT id FROM users WHERE email = 'tatyana@test.ru')
     AND status = 'PLANNED' ORDER BY scheduled_at LIMIT 1),
  (SELECT id FROM users WHERE email = 'tatyana@test.ru'),
  (SELECT id FROM users WHERE email = 'anna@test.ru'),
  'Listening · BBC News A2 (10 мин)',
  'Прослушайте выпуск BBC Learning English. Ответьте на 5 вопросов по тексту.',
  '2026-06-02 23:59:00+03'::timestamptz, 'ASSIGNED'::homework_status, NOW();

-- Просроченная домашка
INSERT INTO homework (teacher_id, student_id, title, description, due_at, status, created_at)
SELECT t.id, s.id,
  'Грамматика: Past Perfect (упр. 1–20)',
  'Выполните упражнения 1–20 из учебника Grammar in Use, стр. 114–116.',
  '2026-05-14 23:59:00+03', 'OVERDUE', NOW() - INTERVAL '16 days'
FROM users s, users t
WHERE s.email = 'anna@test.ru' AND t.email = 'tatyana@test.ru';

-- Submission для проверенного задания
INSERT INTO homework_submissions (homework_id, text_content, submitted_at, grade, feedback, reviewed_at, reviewed_by)
SELECT
  (SELECT id FROM homework WHERE title LIKE '%Quizlet%' LIMIT 1),
  'Выучила все слова! Результат теста Quizlet: 100%. Приложила скриншот.',
  '2026-05-30 14:22:00+03'::timestamptz,
  9,
  'Отлично! Все слова усвоены. Обратите внимание на произношение «anniversaire».',
  '2026-05-30 19:00:00+03'::timestamptz,
  (SELECT id FROM users WHERE email = 'sofia@test.ru')
WHERE EXISTS (SELECT 1 FROM homework WHERE title LIKE '%Quizlet%');

-- ─────────────────────────────────────────
--  ИСТОРИЯ АКТИВНОСТИ (стрик 12 дней)
-- ─────────────────────────────────────────

INSERT INTO daily_activity (student_id, activity_date, score)
SELECT u.id, d::date, score
FROM users u,
(VALUES
  ('2026-05-19', 3), ('2026-05-20', 2), ('2026-05-21', 4),
  ('2026-05-22', 5), ('2026-05-23', 3), ('2026-05-24', 2),
  ('2026-05-25', 4), ('2026-05-26', 5), ('2026-05-27', 3),
  ('2026-05-28', 2), ('2026-05-29', 4), ('2026-05-30', 3)
) AS v(d, score)
WHERE u.email = 'anna@test.ru'
ON CONFLICT (student_id, activity_date) DO NOTHING;

-- ─────────────────────────────────────────
--  СООБЩЕНИЯ
-- ─────────────────────────────────────────

-- Чат Анна ↔ Софья
INSERT INTO conversations DEFAULT VALUES;

INSERT INTO conversation_members (conversation_id, user_id)
SELECT c.id, u.id
FROM (SELECT max(id) AS id FROM conversations) c,
     users u WHERE u.email IN ('anna@test.ru', 'sofia@test.ru');

INSERT INTO messages (conversation_id, sender_id, body, is_read, sent_at)
SELECT c.id, u.id, msg, true, ts::timestamptz
FROM (SELECT max(id) AS id FROM conversations) c,
(VALUES
  ('sofia@test.ru', 'Анна, добрый вечер! Не забудьте про домашнее задание — эссе нужно до понедельника.', '2026-05-29 20:10:00+03'),
  ('anna@test.ru',  'Добрый вечер, Софья! Помню, уже начала писать. Можно уточнить — эссе в свободной форме или по плану?', '2026-05-29 20:35:00+03'),
  ('sofia@test.ru', 'В свободной! Главное — использовать Conditionnel présent как минимум в 5 предложениях. Удачи 😊', '2026-05-29 20:40:00+03'),
  ('anna@test.ru',  'Поняла, спасибо! Пришлю завтра вечером.', '2026-05-29 20:42:00+03')
) AS v(sender_email, msg, ts)
JOIN users u ON u.email = v.sender_email;

-- Чат Анна ↔ Татьяна
INSERT INTO conversations DEFAULT VALUES;

INSERT INTO conversation_members (conversation_id, user_id)
SELECT c.id, u.id
FROM (SELECT max(id) AS id FROM conversations) c,
     users u WHERE u.email IN ('anna@test.ru', 'tatyana@test.ru');

INSERT INTO messages (conversation_id, sender_id, body, is_read, sent_at)
SELECT c.id, u.id, msg, true, ts::timestamptz
FROM (SELECT max(id) AS id FROM conversations) c,
(VALUES
  ('tatyana@test.ru', 'Анна, на следующем уроке разберём Modal verbs. Посмотрите заранее упр. 45–50 в учебнике.', '2026-05-27 18:00:00+03'),
  ('anna@test.ru',    'Хорошо, Татьяна! Уточните — это Grammar in Use или наш учебник?', '2026-05-27 18:30:00+03'),
  ('tatyana@test.ru', 'Grammar in Use — синий, упр. с must/should. До встречи в среду!', '2026-05-27 18:35:00+03')
) AS v(sender_email, msg, ts)
JOIN users u ON u.email = v.sender_email;

-- ─────────────────────────────────────────
--  УВЕДОМЛЕНИЯ
-- ─────────────────────────────────────────

INSERT INTO notifications (user_id, type, title, body, link, is_read, created_at)
SELECT u.id, ntype::notification_type, ntitle, nbody, nlink, nread, nts::timestamptz
FROM users u,
(VALUES
  ('LESSON_REMINDER',       'Урок через 1 час',          'Французский B1 с Софьей Фроловой — сегодня в 18:30', '/calendar', false, '2026-05-30 17:30:00+03'),
  ('HOMEWORK_DUE',          'Домашнее задание до завтра','Эссе «Mes rêves» нужно сдать до 1 июня',             '/homework',  false, '2026-05-30 09:00:00+03'),
  ('SUBSCRIPTION_EXPIRING', 'Абонемент истекает',        'Французский B1 — осталось 3 урока из 8. Продлите заранее.', '/billing', false, '2026-05-29 12:00:00+03'),
  ('NEW_MESSAGE',           'Новое сообщение',           'Софья Фролова: «Анна, добрый вечер! Не забудьте…»', '/messages',  true,  '2026-05-29 20:10:00+03'),
  ('HOMEWORK_DUE',          'Задание проверено',         'Лексика модуля 7 — оценка 9/10. Есть комментарий от преподавателя.', '/homework', true, '2026-05-30 19:00:00+03')
) AS v(ntype, ntitle, nbody, nlink, nread, nts)
WHERE u.email = 'anna@test.ru';

-- ─────────────────────────────────────────
--  НАСТРОЙКИ ПОЛЬЗОВАТЕЛЯ
-- ─────────────────────────────────────────

INSERT INTO user_settings (user_id, notification_email, notification_push, notification_sms, reminder_hours_before, theme, interface_locale)
SELECT id, true, true, false, 1, 'light', 'ru'
FROM users WHERE email = 'anna@test.ru'
ON CONFLICT (user_id) DO NOTHING;
