-- =============================================================
--  P.S. PORTAL — PostgreSQL Schema (полная версия)
--  Запустить в pgAdmin: Tools → Query Tool → Run
-- =============================================================

-- ─────────────────────────────────────────
--  СПРАВОЧНИКИ
-- ─────────────────────────────────────────

CREATE TABLE languages (
    id         SERIAL PRIMARY KEY,
    code       CHAR(2)      NOT NULL UNIQUE,
    name_ru    VARCHAR(64)  NOT NULL,
    flag_emoji VARCHAR(8)
);

CREATE TABLE levels (
    id     SERIAL PRIMARY KEY,
    code   VARCHAR(8)  NOT NULL UNIQUE,
    label  VARCHAR(32) NOT NULL
);

CREATE TABLE rooms (
    id       SERIAL PRIMARY KEY,
    name     VARCHAR(64) NOT NULL,
    capacity SMALLINT    DEFAULT 1,
    zoom_url TEXT
);

-- ─────────────────────────────────────────
--  ПОЛЬЗОВАТЕЛИ
-- ─────────────────────────────────────────

CREATE TYPE user_role AS ENUM ('STUDENT','PARENT','TEACHER','MANAGER','ADMIN');

CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    initials      VARCHAR(8),
    role          user_role    NOT NULL DEFAULT 'STUDENT',
    phone         VARCHAR(32),
    avatar_url    TEXT,
    timezone      VARCHAR(64)  DEFAULT 'Europe/Moscow',
    locale        VARCHAR(8)   DEFAULT 'ru',
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE student_profiles (
    user_id          BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    parent_id        BIGINT REFERENCES users(id),
    current_level_id INT    REFERENCES levels(id),
    streak_days      INT    NOT NULL DEFAULT 0,
    streak_last_date DATE,
    notes            TEXT
);

CREATE TABLE teacher_profiles (
    user_id        BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    bio            TEXT,
    capacity_hours SMALLINT NOT NULL DEFAULT 20,
    is_native      BOOLEAN  NOT NULL DEFAULT FALSE,
    rating         NUMERIC(3,2),
    workload_chip  VARCHAR(16) DEFAULT 'orange'
);

CREATE TABLE teacher_languages (
    teacher_id  BIGINT REFERENCES users(id) ON DELETE CASCADE,
    language_id INT    REFERENCES languages(id),
    level_id    INT    REFERENCES levels(id),
    is_primary  BOOLEAN DEFAULT FALSE,
    PRIMARY KEY (teacher_id, language_id)
);

CREATE TABLE teacher_availability (
    id          BIGSERIAL PRIMARY KEY,
    teacher_id  BIGINT   NOT NULL REFERENCES users(id),
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    time_from   TIME     NOT NULL,
    time_to     TIME     NOT NULL
);

CREATE TABLE refresh_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token      TEXT         NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
--  КУРСЫ
-- ─────────────────────────────────────────

CREATE TABLE courses (
    id            SERIAL       PRIMARY KEY,
    language_id   INT          NOT NULL REFERENCES languages(id),
    level_id      INT          REFERENCES levels(id),
    title         VARCHAR(128) NOT NULL,
    description   TEXT,
    total_lessons SMALLINT     NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE course_modules (
    id        SERIAL       PRIMARY KEY,
    course_id INT          NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title     VARCHAR(128) NOT NULL,
    position  SMALLINT     NOT NULL DEFAULT 0
);

CREATE TYPE material_type AS ENUM ('PDF','VIDEO','AUDIO','LINK','TEXT','EXERCISE');

CREATE TABLE course_materials (
    id         BIGSERIAL     PRIMARY KEY,
    module_id  INT           NOT NULL REFERENCES course_modules(id) ON DELETE CASCADE,
    title      VARCHAR(255)  NOT NULL,
    type       material_type NOT NULL DEFAULT 'PDF',
    url        TEXT,
    position   SMALLINT      NOT NULL DEFAULT 0,
    created_by BIGINT        REFERENCES users(id),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
--  ЗАЧИСЛЕНИЯ
-- ─────────────────────────────────────────

CREATE TABLE enrollments (
    id             BIGSERIAL PRIMARY KEY,
    student_id     BIGINT    NOT NULL REFERENCES users(id),
    course_id      INT       NOT NULL REFERENCES courses(id),
    teacher_id     BIGINT    REFERENCES users(id),
    start_date     DATE,
    end_date       DATE,
    progress_pct   SMALLINT  NOT NULL DEFAULT 0 CHECK (progress_pct BETWEEN 0 AND 100),
    lessons_done   SMALLINT  NOT NULL DEFAULT 0,
    lessons_total  SMALLINT  NOT NULL DEFAULT 0,
    is_active      BOOLEAN   NOT NULL DEFAULT TRUE,
    UNIQUE (student_id, course_id)
);

-- ─────────────────────────────────────────
--  УРОКИ / РАСПИСАНИЕ
-- ─────────────────────────────────────────

CREATE TYPE lesson_format AS ENUM ('INDIVIDUAL','GROUP','SPEAKING_CLUB','TRIAL');
CREATE TYPE lesson_status AS ENUM ('PLANNED','IN_PROGRESS','DONE','MISSED','CANCELLED');
CREATE TYPE lesson_change_type AS ENUM ('CREATED','RESCHEDULED','CANCELLED','RESTORED','STATUS_CHANGED');

CREATE TABLE lessons (
    id                 BIGSERIAL     PRIMARY KEY,
    enrollment_id      BIGINT        REFERENCES enrollments(id),
    teacher_id         BIGINT        NOT NULL REFERENCES users(id),
    language_id        INT           NOT NULL REFERENCES languages(id),
    room_id            INT           REFERENCES rooms(id),
    format             lesson_format NOT NULL DEFAULT 'INDIVIDUAL',
    title              VARCHAR(255),
    topic              VARCHAR(255),
    scheduled_at       TIMESTAMPTZ   NOT NULL,
    duration_min       SMALLINT      NOT NULL DEFAULT 60,
    zoom_url           TEXT,
    status             lesson_status NOT NULL DEFAULT 'PLANNED',
    teacher_note       TEXT,
    cancel_reason      TEXT,
    -- история переносов
    reschedule_count   SMALLINT      NOT NULL DEFAULT 0,
    original_date      TIMESTAMPTZ,
    last_rescheduled_at TIMESTAMPTZ,
    -- timezone на момент создания
    teacher_timezone   VARCHAR(64),
    student_timezone   VARCHAR(64),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE lesson_students (
    lesson_id  BIGINT REFERENCES lessons(id) ON DELETE CASCADE,
    student_id BIGINT REFERENCES users(id),
    attended   BOOLEAN,
    PRIMARY KEY (lesson_id, student_id)
);

-- История изменений урока (перенос, смена статуса)
CREATE TABLE lesson_history (
    id               BIGSERIAL          PRIMARY KEY,
    lesson_id        BIGINT             NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    change_type      lesson_change_type NOT NULL,
    old_scheduled_at TIMESTAMPTZ,
    new_scheduled_at TIMESTAMPTZ,
    old_status       lesson_status,
    new_status       lesson_status,
    changed_by       BIGINT             REFERENCES users(id),
    reason           TEXT,
    changed_at       TIMESTAMPTZ        NOT NULL DEFAULT NOW()
);

-- Event log — полная история любого поля урока
CREATE TABLE lesson_events (
    id         BIGSERIAL   PRIMARY KEY,
    lesson_id  BIGINT      NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    field      VARCHAR(64),
    old_value  TEXT,
    new_value  TEXT,
    reason     TEXT,
    changed_by BIGINT      REFERENCES users(id),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TYPE attention_type AS ENUM ('no_homework','streak_break','overdue_payment','long_absence');

CREATE TABLE student_attention (
    id          BIGSERIAL     PRIMARY KEY,
    teacher_id  BIGINT        NOT NULL REFERENCES users(id),
    student_id  BIGINT        NOT NULL REFERENCES users(id),
    type        attention_type NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ
);

-- ─────────────────────────────────────────
--  АБОНЕМЕНТЫ И ПЛАТЕЖИ
-- ─────────────────────────────────────────

CREATE TABLE subscription_plans (
    id            SERIAL        PRIMARY KEY,
    language_id   INT           REFERENCES languages(id),
    name          VARCHAR(128)  NOT NULL,
    lesson_count  SMALLINT      NOT NULL,
    price         NUMERIC(10,2) NOT NULL,
    validity_days SMALLINT      NOT NULL DEFAULT 30,
    is_active     BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TYPE subscription_status AS ENUM ('ACTIVE','PAUSED','EXPIRED','CANCELLED');

CREATE TABLE subscriptions (
    id            BIGSERIAL           PRIMARY KEY,
    student_id    BIGINT              NOT NULL REFERENCES users(id),
    plan_id       INT                 NOT NULL REFERENCES subscription_plans(id),
    start_date    DATE                NOT NULL,
    end_date      DATE                NOT NULL,
    lessons_used  SMALLINT            NOT NULL DEFAULT 0,
    lessons_total SMALLINT            NOT NULL,
    status        subscription_status NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ         NOT NULL DEFAULT NOW()
);

CREATE TYPE payment_method AS ENUM ('CARD','SBP','CASH','TRANSFER');
CREATE TYPE payment_status AS ENUM ('PAID','PENDING','OVERDUE','REFUNDED');

CREATE TABLE payments (
    id              BIGSERIAL      PRIMARY KEY,
    student_id      BIGINT         NOT NULL REFERENCES users(id),
    subscription_id BIGINT         REFERENCES subscriptions(id),
    amount          NUMERIC(10,2)  NOT NULL,
    method          payment_method NOT NULL DEFAULT 'CARD',
    status          payment_status NOT NULL DEFAULT 'PENDING',
    paid_at         TIMESTAMPTZ,
    due_date        DATE,
    invoice_url     TEXT,
    notes           TEXT,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE payment_refunds (
    id           BIGSERIAL     PRIMARY KEY,
    payment_id   BIGINT        NOT NULL REFERENCES payments(id),
    amount       NUMERIC(10,2) NOT NULL,
    reason       TEXT,
    refunded_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    processed_by BIGINT        REFERENCES users(id)
);

-- ─────────────────────────────────────────
--  ДОМАШНИЕ ЗАДАНИЯ
-- ─────────────────────────────────────────

CREATE TYPE homework_status AS ENUM ('ASSIGNED','SUBMITTED','REVIEWED','OVERDUE');

CREATE TABLE homework (
    id         BIGSERIAL       PRIMARY KEY,
    lesson_id  BIGINT          REFERENCES lessons(id),
    teacher_id BIGINT          NOT NULL REFERENCES users(id),
    student_id BIGINT          NOT NULL REFERENCES users(id),
    title      VARCHAR(255)    NOT NULL,
    description TEXT,
    due_at     TIMESTAMPTZ,
    status     homework_status NOT NULL DEFAULT 'ASSIGNED',
    created_at TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE homework_submissions (
    id           BIGSERIAL   PRIMARY KEY,
    homework_id  BIGINT      NOT NULL REFERENCES homework(id) ON DELETE CASCADE,
    file_url     TEXT,
    text_content TEXT,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    grade        SMALLINT    CHECK (grade BETWEEN 1 AND 10),
    feedback     TEXT,
    reviewed_at  TIMESTAMPTZ,
    reviewed_by  BIGINT      REFERENCES users(id)
);

-- ─────────────────────────────────────────
--  СООБЩЕНИЯ
-- ─────────────────────────────────────────

CREATE TABLE conversations (
    id         BIGSERIAL   PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE conversation_members (
    conversation_id BIGINT REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         BIGINT REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE messages (
    id              BIGSERIAL   PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversations(id),
    sender_id       BIGINT      NOT NULL REFERENCES users(id),
    body            TEXT        NOT NULL,
    attachment_url  TEXT,
    is_read         BOOLEAN     NOT NULL DEFAULT FALSE,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
--  УВЕДОМЛЕНИЯ
-- ─────────────────────────────────────────

CREATE TYPE notification_type AS ENUM (
    'LESSON_REMINDER','HOMEWORK_DUE','PAYMENT_DUE','PAYMENT_OVERDUE',
    'NEW_MESSAGE','SUBSCRIPTION_EXPIRING','SYSTEM'
);

CREATE TABLE notifications (
    id         BIGSERIAL         PRIMARY KEY,
    user_id    BIGINT            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       notification_type NOT NULL,
    title      VARCHAR(255)      NOT NULL,
    body       TEXT,
    link       TEXT,
    is_read    BOOLEAN           NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ       NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
--  ЛИДЫ / CRM
-- ─────────────────────────────────────────

CREATE TYPE lead_status AS ENUM ('NEW','IN_PROGRESS','TRIAL_SCHEDULED','CONVERTED','LOST');

CREATE TABLE leads (
    id                BIGSERIAL   PRIMARY KEY,
    name              VARCHAR(128) NOT NULL,
    phone             VARCHAR(32),
    email             VARCHAR(255),
    language_id       INT          REFERENCES languages(id),
    level_id          INT          REFERENCES levels(id),
    preferred_time    VARCHAR(128),
    frequency         VARCHAR(64),
    source            VARCHAR(64),
    status            lead_status  NOT NULL DEFAULT 'NEW',
    assigned_to       BIGINT       REFERENCES users(id),
    notes             TEXT,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    converted_at      TIMESTAMPTZ,
    converted_user_id BIGINT       REFERENCES users(id)
);

CREATE TABLE lead_activities (
    id          BIGSERIAL   PRIMARY KEY,
    lead_id     BIGINT      NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL REFERENCES users(id),
    type        VARCHAR(64) NOT NULL,
    notes       TEXT,
    happened_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
--  МАТРИЦА ДОСТУПА
-- ─────────────────────────────────────────

CREATE TABLE access_matrix (
    id          SERIAL       PRIMARY KEY,
    module_key  VARCHAR(64)  NOT NULL,
    module_name VARCHAR(128) NOT NULL,
    role        user_role    NOT NULL,
    can_read    BOOLEAN      NOT NULL DEFAULT FALSE,
    can_write   BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (module_key, role)
);

-- ─────────────────────────────────────────
--  СТРИК / АКТИВНОСТЬ
-- ─────────────────────────────────────────

CREATE TABLE daily_activity (
    student_id    BIGINT   REFERENCES users(id) ON DELETE CASCADE,
    activity_date DATE     NOT NULL,
    score         SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (student_id, activity_date)
);

CREATE TABLE teacher_daily_hours (
    teacher_id BIGINT        REFERENCES users(id) ON DELETE CASCADE,
    work_date  DATE          NOT NULL,
    hours      NUMERIC(4,1)  NOT NULL DEFAULT 0,
    PRIMARY KEY (teacher_id, work_date)
);

-- ─────────────────────────────────────────
--  ОТЧЁТЫ
-- ─────────────────────────────────────────

CREATE TYPE report_type AS ENUM ('REVENUE','ATTENDANCE','HOMEWORK','TEACHERS','LEADS');

CREATE TABLE reports (
    id         BIGSERIAL   PRIMARY KEY,
    type       report_type NOT NULL,
    title      VARCHAR(255) NOT NULL,
    params     JSONB,
    result_url TEXT,
    created_by BIGINT      REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
--  НАСТРОЙКИ
-- ─────────────────────────────────────────

CREATE TABLE system_settings (
    key         VARCHAR(128) PRIMARY KEY,
    value       TEXT         NOT NULL,
    description TEXT,
    updated_by  BIGINT       REFERENCES users(id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE user_settings (
    user_id               BIGINT   PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    notification_email    BOOLEAN  NOT NULL DEFAULT TRUE,
    notification_push     BOOLEAN  NOT NULL DEFAULT TRUE,
    notification_sms      BOOLEAN  NOT NULL DEFAULT FALSE,
    reminder_hours_before SMALLINT NOT NULL DEFAULT 24,
    theme                 VARCHAR(16) DEFAULT 'light',
    interface_locale      VARCHAR(8)  DEFAULT 'ru',
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
--  РАЗГОВОРНЫЙ КЛУБ
-- ─────────────────────────────────────────

CREATE TABLE speaking_clubs (
    id           BIGSERIAL     PRIMARY KEY,
    language_id  INT           NOT NULL REFERENCES languages(id),
    teacher_id   BIGINT        NOT NULL REFERENCES users(id),
    title        VARCHAR(255)  NOT NULL,
    topic        TEXT,
    scheduled_at TIMESTAMPTZ   NOT NULL,
    duration_min SMALLINT      NOT NULL DEFAULT 60,
    max_students SMALLINT      NOT NULL DEFAULT 8,
    zoom_url     TEXT,
    room_id      INT           REFERENCES rooms(id),
    status       lesson_status NOT NULL DEFAULT 'PLANNED'
);

CREATE TABLE speaking_club_registrations (
    club_id       BIGINT      REFERENCES speaking_clubs(id) ON DELETE CASCADE,
    student_id    BIGINT      REFERENCES users(id),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attended      BOOLEAN,
    PRIMARY KEY (club_id, student_id)
);

-- ─────────────────────────────────────────
--  AUDIT LOG
-- ─────────────────────────────────────────

CREATE TABLE audit_log (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       REFERENCES users(id),
    action      VARCHAR(128) NOT NULL,
    entity_type VARCHAR(64),
    entity_id   BIGINT,
    old_value   JSONB,
    new_value   JSONB,
    ip_address  INET,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────
--  ИНДЕКСЫ
-- ─────────────────────────────────────────

CREATE INDEX idx_lessons_teacher_scheduled    ON lessons(teacher_id, scheduled_at);
CREATE INDEX idx_lessons_scheduled_status     ON lessons(scheduled_at, status);
CREATE INDEX idx_homework_student_status      ON homework(student_id, status);
CREATE INDEX idx_payments_student_status      ON payments(student_id, status);
CREATE INDEX idx_subscriptions_student_status ON subscriptions(student_id, status);
CREATE INDEX idx_notifications_user_unread    ON notifications(user_id) WHERE NOT is_read;
CREATE INDEX idx_messages_conversation        ON messages(conversation_id, sent_at DESC);
CREATE INDEX idx_leads_status_assigned        ON leads(status, assigned_to);
CREATE INDEX idx_daily_activity_student_date  ON daily_activity(student_id, activity_date DESC);
CREATE INDEX idx_audit_log_entity             ON audit_log(entity_type, entity_id);
CREATE INDEX idx_lesson_history_lesson        ON lesson_history(lesson_id, changed_at DESC);
CREATE INDEX idx_lesson_events_lesson         ON lesson_events(lesson_id, changed_at DESC);
CREATE INDEX idx_lesson_events_type           ON lesson_events(event_type, changed_at DESC);
