-- The SQLite half of "cascade the two user foreign keys" (see the postgresql/ copy for why).
--
-- SQLite cannot alter a constraint — there is no ALTER TABLE ... DROP CONSTRAINT at any version —
-- so both tables are rebuilt, the same way V4 had to rebuild `users`. As there, this is only safe
-- because nothing enables `PRAGMA foreign_keys` (DataSourceFactory and TestDataSource both leave
-- it at SQLite's OFF default), and the pragma is a no-op inside the transaction Flyway runs each
-- migration in, so it could not be toggled here even if that changed. Every id is copied verbatim,
-- so the rows other tables point at are the same rows afterwards.
--
-- Note the flip side of that pragma: no foreign key declared in this dialect's migrations is ever
-- enforced at runtime, so this file changes no SQLite behaviour at all. It exists to keep the two
-- schemas identical, which is what makes the Postgres migrations reviewable against tests that run
-- on SQLite.

CREATE TABLE group_pairs_new (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id         INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    source           TEXT NOT NULL,
    target           TEXT NOT NULL,
    created_by       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_by_email TEXT NOT NULL,
    created_at       INTEGER NOT NULL
);

INSERT INTO group_pairs_new (id, group_id, source, target, created_by, created_by_email, created_at)
SELECT id, group_id, source, target, created_by, created_by_email, created_at FROM group_pairs;

-- Takes idx_group_pairs_group_id with it, hence the recreate below.
DROP TABLE group_pairs;

ALTER TABLE group_pairs_new RENAME TO group_pairs;

CREATE INDEX idx_group_pairs_group_id ON group_pairs(group_id);

CREATE TABLE group_invitations_new (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id    INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    email       TEXT NOT NULL,
    role        TEXT NOT NULL,
    token       TEXT NOT NULL UNIQUE,
    invited_by  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    accepted_at INTEGER
);

INSERT INTO group_invitations_new (id, group_id, email, role, token, invited_by, created_at, expires_at, accepted_at)
SELECT id, group_id, email, role, token, invited_by, created_at, expires_at, accepted_at FROM group_invitations;

DROP TABLE group_invitations;

ALTER TABLE group_invitations_new RENAME TO group_invitations;

CREATE INDEX idx_group_invitations_group_id ON group_invitations(group_id);
