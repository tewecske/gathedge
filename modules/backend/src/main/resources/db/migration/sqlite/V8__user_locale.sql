-- See the postgresql copy for what this column is for. As in V5, ADD COLUMN is one of the few
-- ALTER TABLE forms SQLite supports, so this needs no table rebuild — unlike V4 and V6, which had
-- to rebuild `users` to drop a UNIQUE column and to change a constraint.
ALTER TABLE users ADD COLUMN locale TEXT NOT NULL DEFAULT 'en';
