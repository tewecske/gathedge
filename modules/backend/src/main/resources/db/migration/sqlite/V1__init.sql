-- The SQLite mirror of postgresql/V1__init.sql. See that file for what each table is for and for
-- the two schema-wide conventions (epoch-millis timestamps, and SET NULL rather than CASCADE on
-- the two observability tables).
--
-- SQLite backs tests only — production is always Postgres. This dialect exists so `sbt test` needs
-- no Docker, and the two files are kept statement-for-statement equivalent so that a test passing
-- here says something about the schema that ships.
--
-- Nothing enables `PRAGMA foreign_keys` (neither `DataSourceFactory` nor `TestDataSource` turns it
-- on), so every constraint below is decorative here: SQLite enforces no foreign key at all. They
-- are declared anyway to keep the two schemas identical. The consequence is that no cascade and no
-- constraint violation is exercised by anything except `PostgresIntegrationSpec`.

CREATE TABLE users (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    email             TEXT NOT NULL UNIQUE,
    password_hash     TEXT,
    is_admin          BOOLEAN NOT NULL DEFAULT 0,
    theme             TEXT NOT NULL DEFAULT 'light',
    created_at        INTEGER NOT NULL,
    email_verified_at INTEGER,
    locale            TEXT NOT NULL DEFAULT 'en'
);

CREATE TABLE sessions (
    id         TEXT PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER
);

CREATE INDEX idx_sessions_user_id ON sessions(user_id);

CREATE TABLE oauth_identities (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider   TEXT NOT NULL,
    subject    TEXT NOT NULL,
    email      TEXT,
    created_at INTEGER NOT NULL,
    UNIQUE (provider, subject)
);

CREATE INDEX idx_oauth_identities_user_id ON oauth_identities(user_id);

CREATE TABLE email_verification_tokens (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT NOT NULL UNIQUE,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);

CREATE TABLE login_attempts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    email      TEXT NOT NULL,
    user_id    INTEGER REFERENCES users(id) ON DELETE SET NULL,
    ip         TEXT,
    outcome    TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_login_attempts_email ON login_attempts(email, created_at);
CREATE INDEX idx_login_attempts_user_id ON login_attempts(user_id);
CREATE INDEX idx_login_attempts_created_at ON login_attempts(created_at);

CREATE TABLE audit_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at   INTEGER NOT NULL,
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    actor_email   TEXT,
    action        TEXT NOT NULL,
    target_type   TEXT,
    target_id     TEXT,
    detail        TEXT,
    ip            TEXT
);

CREATE INDEX idx_audit_log_occurred_at ON audit_log(occurred_at);
CREATE INDEX idx_audit_log_actor_user_id ON audit_log(actor_user_id);
CREATE INDEX idx_audit_log_target ON audit_log(target_type, target_id);
