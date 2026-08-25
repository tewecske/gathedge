-- The SQLite mirror of postgresql/V15__groups.sql. See that file for what each table is for and why
-- `created_by` is SET NULL while `group_members.user_id` cascades. As everywhere else in this schema
-- no foreign key is actually enforced, so the cascades/SET NULL are exercised only by
-- PostgresIntegrationSpec. `ADD COLUMN` needs no table rebuild, same as every other SQLite migration
-- in this tree that only adds a column.
CREATE TABLE groups (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT NOT NULL,
    name_norm    TEXT NOT NULL,
    invite_code  TEXT NOT NULL UNIQUE,
    created_by   INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at   INTEGER NOT NULL
);

CREATE INDEX idx_groups_name_norm ON groups(name_norm);

CREATE TABLE group_members (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id   INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id    INTEGER NOT NULL REFERENCES users(id)  ON DELETE CASCADE,
    role       TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (group_id, user_id)
);

CREATE INDEX idx_group_members_group ON group_members(group_id);
CREATE INDEX idx_group_members_user  ON group_members(user_id);

ALTER TABLE tags ADD COLUMN group_id INTEGER REFERENCES groups(id) ON DELETE SET NULL;

CREATE INDEX idx_tags_group ON tags(group_id);
