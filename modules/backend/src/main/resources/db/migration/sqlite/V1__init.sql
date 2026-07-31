CREATE TABLE users (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    email          TEXT NOT NULL UNIQUE,
    password_hash  TEXT,
    is_admin       BOOLEAN NOT NULL DEFAULT 0,
    theme          TEXT NOT NULL DEFAULT 'light',
    google_subject TEXT UNIQUE,
    created_at     INTEGER NOT NULL
);

CREATE TABLE sessions (
    id         TEXT PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER
);

CREATE INDEX idx_sessions_user_id ON sessions(user_id);
