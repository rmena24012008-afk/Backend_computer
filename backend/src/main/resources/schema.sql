-- ============================================
-- DATABASE: ai_task_agent
-- ============================================

CREATE DATABASE IF NOT EXISTS ai_task_agent;
USE ai_task_agent;

-- ── Users ──
CREATE TABLE IF NOT EXISTS users (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    username        VARCHAR(50) UNIQUE NOT NULL,
    email           VARCHAR(100) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    preferences     JSON         DEFAULT NULL,
    theme           VARCHAR(30)  DEFAULT 'light',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ── Chat Sessions ──
CREATE TABLE IF NOT EXISTS chat_sessions (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    title           VARCHAR(255) DEFAULT 'New conversation',
    summary         TEXT         DEFAULT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ── Chat Messages ──
CREATE TABLE IF NOT EXISTS chat_messages (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id      BIGINT NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

-- ── Projects (created by AI on remote machine) ──
CREATE TABLE IF NOT EXISTS projects (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    session_id      BIGINT,
    project_id      VARCHAR(100) UNIQUE NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    files           JSON NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE SET NULL
);

-- ── Scheduled Tasks ──
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    session_id      BIGINT,
    task_id         VARCHAR(100) UNIQUE NOT NULL,
    description     TEXT,
    status          VARCHAR(20) DEFAULT 'scheduled',
    interval_secs   INT NOT NULL,
    started_at      TIMESTAMP NULL,
    ends_at         TIMESTAMP NOT NULL,
    total_runs      INT DEFAULT 0,
    completed_runs  INT DEFAULT 0,
    output_file     VARCHAR(500),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE SET NULL
);

-- ── Task Run Logs ──
CREATE TABLE IF NOT EXISTS task_run_logs (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id         VARCHAR(100) NOT NULL,
    run_number      INT NOT NULL,
    status          VARCHAR(20) DEFAULT 'success',
    result_data     JSON DEFAULT NULL,
    error_message   TEXT,
    executed_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES scheduled_tasks(task_id) ON DELETE CASCADE
);

-- ── Auth Tokens (OAuth + encrypted credentials) ── v1.2 (Zoho)
CREATE TABLE IF NOT EXISTS auth_tokens (
    id               BIGINT        PRIMARY KEY AUTO_INCREMENT,
    user_id          BIGINT        NOT NULL,
    provider         VARCHAR(100)  NOT NULL,
    header_type      VARCHAR(50)   DEFAULT 'Bearer',
    access_token     TEXT          NOT NULL,
    refresh_token    TEXT          DEFAULT NULL,
    expires_at       TIMESTAMP     DEFAULT (NOW() + INTERVAL 1 HOUR),
    client_id        VARCHAR(255)  DEFAULT NULL,
    client_secret    TEXT          DEFAULT NULL,
    token_endpoint   VARCHAR(500)  DEFAULT NULL,
    oauth_token_link VARCHAR(1000) DEFAULT NULL,
    scope            VARCHAR(1000) DEFAULT NULL,
    updated_at       TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uq_user_provider (user_id, provider)
);

-- ── Indexes ──
CREATE INDEX idx_sessions_user       ON chat_sessions(user_id);
CREATE INDEX idx_messages_session    ON chat_messages(session_id);
CREATE INDEX idx_projects_user       ON projects(user_id);
CREATE INDEX idx_tasks_user          ON scheduled_tasks(user_id);
CREATE INDEX idx_task_logs_task      ON task_run_logs(task_id);
CREATE INDEX idx_auth_tokens_user    ON auth_tokens(user_id);
CREATE INDEX idx_auth_tokens_expires ON auth_tokens(expires_at);


-- ============================================
-- MIGRATION v1.1 — for existing databases
-- (Safe to run against a DB created from the original schema above)
-- ============================================

-- Change 1: Add summary to chat_sessions
ALTER TABLE chat_sessions
    ADD COLUMN IF NOT EXISTS summary TEXT DEFAULT NULL AFTER title;

-- Change 2: Add preferences to users
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS preferences JSON DEFAULT NULL AFTER password_hash;

-- Change 3: auth_tokens table is already defined above via CREATE TABLE IF NOT EXISTS

-- Rollback (destructive — use only if needed):
-- ALTER TABLE chat_sessions DROP COLUMN summary;
-- ALTER TABLE users DROP COLUMN preferences;
-- DROP TABLE IF EXISTS auth_tokens;

-- ============================================
-- MIGRATION v1.2 -- Zoho OAuth scope support
-- (Safe to run against databases created from earlier schema versions)
-- ============================================

-- Add scope column to auth_tokens (idempotent -- no-op if column already exists)
ALTER TABLE auth_tokens
    ADD COLUMN IF NOT EXISTS scope VARCHAR(1000) DEFAULT NULL AFTER oauth_token_link;
