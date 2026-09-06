-- The SQLite mirror of postgresql/V26__optimistic_lock_version.sql. See that file for what the column
-- is for. `ADD COLUMN` needs no table rebuild: `version` is in no index, constraint or view.
ALTER TABLE tags          ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE groups        ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE group_members ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE games         ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users         ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
