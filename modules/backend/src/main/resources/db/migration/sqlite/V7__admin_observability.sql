-- Mirror of the Postgres V7. Both tables are new, so unlike V4 and V6 this needs no table rebuild:
-- the ON DELETE SET NULL actions can be declared inline. They are decorative here in any case —
-- nothing enables PRAGMA foreign_keys, so SQLite enforces no foreign key at all and the referential
-- behaviour of these columns is only ever exercised by PostgresIntegrationSpec.

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
