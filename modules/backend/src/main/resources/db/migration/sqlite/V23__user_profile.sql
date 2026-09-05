-- The account's own profile: a name to be called by, and a username to sign in with.
--
-- Both are optional. An account that never fills them in signs in with its address, exactly as before -- which is why
-- neither column can be `NOT NULL` and why the unique index below has to tolerate many NULLs. Postgres and SQLite both
-- treat NULLs in a unique index as distinct, so every account without a username coexists under it.
--
-- `username` is stored lowercased, the same rule `users.email` follows and for the same reason: it makes the unique
-- index the whole of the case-insensitive uniqueness, with no `lower()` in the SQL for the two dialects to disagree
-- about. Casing a reader cares about belongs in `display_name`, which nothing matches on.
--
-- `display_name` is not `name`: `name` is a reserved-ish word in enough tooling to be worth avoiding, and the column
-- says what it is for -- what the account menu calls this account, never what a sign-in resolves.
ALTER TABLE users ADD COLUMN username VARCHAR(32);
ALTER TABLE users ADD COLUMN display_name VARCHAR(255);

CREATE UNIQUE INDEX idx_users_username ON users(username);
