-- ── Пользователи ─────────────────────────────────────────────────────────────
INSERT INTO app_users (name, email, password, role, initials, subtitle) VALUES
  ('Анна Соколова',     'anna@test.ru',    '$2a$10$2z6lV0EWzUFchbmI/T6UuuVvHzc/LeNVA3iRPmyqGt/oVV8RGpi6m', 'STUDENT', 'АС', 'Ученик · французский B1'),
  ('Софья Фролова',     'sofia@test.ru',   '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'TEACHER', 'СФ', 'Преподаватель · фр, англ'),
  ('Татьяна Кравченко', 'tatyana@test.ru', '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'TEACHER', 'ТК', 'Преподаватель · англ, фр'),
  ('Pierre Bouchard',   'pierre@test.ru',  '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'TEACHER', 'PB', 'Преподаватель · носитель (фр)'),
  ('Лаура Мартин',      'laura@test.ru',   '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'TEACHER', 'ЛМ', 'Преподаватель · исп, фр'),
  ('Иван Шульц',        'ivan@test.ru',    '$2a$10$M8eHK2UmmI2qlK9G2Wh7OekzqYHQjEEWbWzyedjZANEQWckJH1UpC', 'TEACHER', 'ИШ', 'Преподаватель · нем, англ'),
  ('Михаил Фролов',     'admin@test.ru',   '$2a$10$jAb4PISNF75./HLDlKtS3evj5Y54bfHPe56dahSTMO6vM38miI6/W',  'ADMIN',   'МФ', 'Администратор · Post Scriptum')
ON CONFLICT (email) DO NOTHING;

-- ── Профили преподавателей ────────────────────────────────────────────────────
INSERT INTO teacher_profiles (user_id, bio, experience, rating, review_count, native_speaker, languages, tags, primary_lang)
SELECT id, 'Специализируюсь на разговорном французском и подготовке к DELF/DALF. Люблю строить уроки вокруг реальных ситуаций — кафе, путешествия, переговоры.', '8 лет', 4.9, 47, false, 'Французский B2+,Английский B1', 'Разговорный,DELF/DALF,Грамматика,Бизнес-французский', 'fr'
FROM app_users WHERE email = 'sofia@test.ru'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO teacher_profiles (user_id, bio, experience, rating, review_count, native_speaker, languages, tags, primary_lang)
SELECT id, 'Фокус на деловом английском и подготовке к IELTS/TOEFL. Работаю со взрослыми и подростками с уровня A2.', '6 лет', 4.8, 31, false, 'Английский C1,Французский A2', 'IELTS,Деловой,Произношение,Грамматика', 'en'
FROM app_users WHERE email = 'tatyana@test.ru'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO teacher_profiles (user_id, bio, experience, rating, review_count, native_speaker, languages, tags, primary_lang)
SELECT id, 'Родился и вырос в Лионе. Веду разговорные клубы и помогаю поставить настоящий французский акцент. Уроки живые и неформальные.', '4 года', 5.0, 22, true, 'Французский (родной),Английский B2', 'Speaking Club,Произношение,Разговорный,Носитель', 'fr'
FROM app_users WHERE email = 'pierre@test.ru'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO teacher_profiles (user_id, bio, experience, rating, review_count, native_speaker, languages, tags, primary_lang)
SELECT id, 'Специализируюсь на испанском — от нуля до уверенного разговора. Использую метод погружения и много практики.', '5 лет', 4.7, 19, false, 'Испанский C2,Французский B1', 'Испанский,Разговорный,Грамматика,DELE', 'es'
FROM app_users WHERE email = 'laura@test.ru'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO teacher_profiles (user_id, bio, experience, rating, review_count, native_speaker, languages, tags, primary_lang)
SELECT id, 'Веду немецкий с нуля до B2. Объясняю грамматику простым языком, много практики через диалоги.', '3 года', 4.6, 15, false, 'Немецкий C1,Английский B2', 'Немецкий,Грамматика,TestDaF,Начинающие', 'de'
FROM app_users WHERE email = 'ivan@test.ru'
ON CONFLICT (user_id) DO NOTHING;

-- ── Зачисления ────────────────────────────────────────────────────────────────
INSERT INTO enrollments (student_id, teacher_id, language, level, status, start_date, progress)
SELECT s.id, t.id, 'fr', 'B1', 'ACTIVE', '2026-01-15', 72
FROM app_users s CROSS JOIN app_users t
WHERE s.email = 'anna@test.ru' AND t.email = 'sofia@test.ru'
  AND NOT EXISTS (SELECT 1 FROM enrollments e WHERE e.student_id = s.id AND e.teacher_id = t.id AND e.language = 'fr');

INSERT INTO enrollments (student_id, teacher_id, language, level, status, start_date, progress)
SELECT s.id, t.id, 'en', 'A2', 'ACTIVE', '2026-02-01', 41
FROM app_users s CROSS JOIN app_users t
WHERE s.email = 'anna@test.ru' AND t.email = 'tatyana@test.ru'
  AND NOT EXISTS (SELECT 1 FROM enrollments e WHERE e.student_id = s.id AND e.teacher_id = t.id AND e.language = 'en');

-- ── Уроки: Анна + Софья (французский) ────────────────────────────────────────
DO $$
DECLARE
  v_student BIGINT := (SELECT id FROM app_users WHERE email = 'anna@test.ru');
  v_teacher BIGINT := (SELECT id FROM app_users WHERE email = 'sofia@test.ru');
  v_enroll  BIGINT := (SELECT id FROM enrollments WHERE student_id = v_student AND teacher_id = v_teacher AND language = 'fr');
BEGIN
  INSERT INTO lessons (teacher_id, student_id, enrollment_id, scheduled_at, duration_min, lang, status)
  SELECT v_teacher, v_student, v_enroll, ts, 60, 'fr', st
  FROM (VALUES
    ('2026-05-12 18:30:00'::timestamp, 'DONE'),
    ('2026-05-15 18:30:00'::timestamp, 'DONE'),
    ('2026-05-19 18:30:00'::timestamp, 'PLANNED'),
    ('2026-05-22 18:30:00'::timestamp, 'PLANNED'),
    ('2026-05-26 18:30:00'::timestamp, 'PLANNED')
  ) AS v(ts, st)
  WHERE NOT EXISTS (
    SELECT 1 FROM lessons l WHERE l.teacher_id = v_teacher AND l.student_id = v_student AND l.scheduled_at = v.ts
  );
END $$;

-- ── Уроки: Анна + Татьяна (английский) ───────────────────────────────────────
DO $$
DECLARE
  v_student BIGINT := (SELECT id FROM app_users WHERE email = 'anna@test.ru');
  v_teacher BIGINT := (SELECT id FROM app_users WHERE email = 'tatyana@test.ru');
  v_enroll  BIGINT := (SELECT id FROM enrollments WHERE student_id = v_student AND teacher_id = v_teacher AND language = 'en');
BEGIN
  INSERT INTO lessons (teacher_id, student_id, enrollment_id, scheduled_at, duration_min, lang, status)
  SELECT v_teacher, v_student, v_enroll, ts, 60, 'en', st
  FROM (VALUES
    ('2026-05-14 19:00:00'::timestamp, 'DONE'),
    ('2026-05-21 19:00:00'::timestamp, 'PLANNED'),
    ('2026-05-28 19:00:00'::timestamp, 'PLANNED')
  ) AS v(ts, st)
  WHERE NOT EXISTS (
    SELECT 1 FROM lessons l WHERE l.teacher_id = v_teacher AND l.student_id = v_student AND l.scheduled_at = v.ts
  );
END $$;
