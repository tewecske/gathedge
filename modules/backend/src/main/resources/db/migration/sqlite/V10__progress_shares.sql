-- The SQLite mirror of postgresql/V10__progress_shares.sql. See that file for what each table is for.
--
-- As everywhere else in this schema no foreign key is actually enforced (nothing enables
-- `PRAGMA foreign_keys`), so the cascades are exercised only by PostgresIntegrationSpec.
CREATE TABLE progress_share_codes (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code         TEXT NOT NULL UNIQUE,
    created_at   INTEGER NOT NULL,
    last_used_at INTEGER,
    revoked_at   INTEGER
);

CREATE INDEX idx_progress_share_codes_user_id ON progress_share_codes(user_id);

CREATE TABLE progress_shares (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    sharer_user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewer_user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      INTEGER NOT NULL,
    UNIQUE (sharer_user_id, viewer_user_id)
);

CREATE INDEX idx_progress_shares_sharer ON progress_shares(sharer_user_id);
CREATE INDEX idx_progress_shares_viewer ON progress_shares(viewer_user_id);
