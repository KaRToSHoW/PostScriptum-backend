-- Пользователи
CREATE TABLE IF NOT EXISTS app_users (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    email    VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    role     VARCHAR(50)  NOT NULL,
    initials VARCHAR(10),
    subtitle VARCHAR(255)
);

-- Профили преподавателей
CREATE TABLE IF NOT EXISTS teacher_profiles (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT UNIQUE REFERENCES app_users(id),
    bio            TEXT,
    experience     VARCHAR(100),
    rating         DOUBLE PRECISION,
    review_count   INTEGER,
    native_speaker BOOLEAN DEFAULT FALSE,
    languages      VARCHAR(500),
    tags           VARCHAR(500),
    primary_lang   VARCHAR(10)
);

-- Зачисления (ученик ↔ преподаватель × язык)
CREATE TABLE IF NOT EXISTS enrollments (
    id           BIGSERIAL PRIMARY KEY,
    student_id   BIGINT REFERENCES app_users(id),
    teacher_id   BIGINT REFERENCES app_users(id),
    language     VARCHAR(10),
    level        VARCHAR(20),
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_date   DATE,
    progress     INTEGER DEFAULT 0
);

-- Уроки
CREATE TABLE IF NOT EXISTS lessons (
    id            BIGSERIAL PRIMARY KEY,
    teacher_id    BIGINT REFERENCES app_users(id),
    student_id    BIGINT REFERENCES app_users(id),
    enrollment_id BIGINT REFERENCES enrollments(id),
    scheduled_at  TIMESTAMP,
    duration_min  INTEGER,
    lang          VARCHAR(10),
    status        VARCHAR(20),
    zoom_url      VARCHAR(500),
    group_label   VARCHAR(255)
);
