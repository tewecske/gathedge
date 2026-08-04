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

INSERT INTO oauth_identities (user_id, provider, subject, email, created_at)
SELECT id, 'google', google_subject, email, created_at FROM users WHERE google_subject IS NOT NULL;

-- The Postgres side of this migration is a plain `ALTER TABLE users DROP COLUMN google_subject`.
-- SQLite refuses that one: the column carries a UNIQUE constraint from V1__init.sql, and
-- "cannot drop UNIQUE column" holds regardless of SQLite version (the pinned sqlite-jdbc is
-- 3.53.2.0, far past the 3.35 that introduced DROP COLUMN at all). So the table is rebuilt instead.
--
-- `sessions.user_id` has a foreign key onto `users`, which survives this only because SQLite leaves
-- `PRAGMA foreign_keys` OFF by default and nothing in DataSourceFactory or TestDataSource turns it
-- on. Enabling it would need this rebuild reworked — and the pragma is a no-op inside the
-- transaction Flyway runs each migration in, so it could not simply be toggled here.
-- Every id is copied verbatim, so the rows `sessions` points at are the same rows afterwards.
CREATE TABLE users_new (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT,
    is_admin      BOOLEAN NOT NULL DEFAULT 0,
    theme         TEXT NOT NULL DEFAULT 'light',
    created_at    INTEGER NOT NULL
);

INSERT INTO users_new (id, email, password_hash, is_admin, theme, created_at)
SELECT id, email, password_hash, is_admin, theme, created_at FROM users;

DROP TABLE users;

ALTER TABLE users_new RENAME TO users;
