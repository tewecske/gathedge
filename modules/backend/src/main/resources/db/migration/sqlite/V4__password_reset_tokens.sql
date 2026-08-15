-- The SQLite mirror of postgresql/V4__password_reset_tokens.sql. See that file for what the table is
-- for; see V1__init.sql for why nothing here enforces the foreign key.
CREATE TABLE password_reset_tokens (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT NOT NULL UNIQUE,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
