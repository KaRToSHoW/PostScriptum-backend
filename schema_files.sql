-- =============================================================
--  P.S. PORTAL — Доп. таблица для хранения файлов
--  Запустить после schema.sql
-- =============================================================

CREATE TABLE IF NOT EXISTS stored_files (
    id            BIGSERIAL    PRIMARY KEY,
    storage_name  VARCHAR(255) NOT NULL UNIQUE,   -- имя файла на диске (uuid.ext)
    original_name VARCHAR(255) NOT NULL,          -- оригинальное имя
    content_type  VARCHAR(128),
    size_bytes    BIGINT,
    uploaded_by   BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    purpose       VARCHAR(32)  DEFAULT 'GENERAL', -- AVATAR | HOMEWORK | MATERIAL | MESSAGE
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_stored_files_uploader ON stored_files(uploaded_by);
