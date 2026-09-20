-- One row per account: its daily play streak and the counts the profile page shows next to it.
--
-- `user_id` is the primary key, so the mapping to `users` is one to one. It cascades, like every other table that holds
-- personal data tied to one account (see V17__game_favorites.sql): nothing here has to outlive the account.
--
-- `last_play_day` is a UTC epoch day, not a timestamp. A streak counts whole days, and one column of days needs no time
-- zone arithmetic in SQL. A row exists only after the account's first play, so a missing row means "never played".
CREATE TABLE user_streaks (
    user_id        BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    current_streak INTEGER NOT NULL,
    longest_streak INTEGER NOT NULL,
    total_days     INTEGER NOT NULL,
    last_play_day  BIGINT NOT NULL,
    updated_at     BIGINT NOT NULL
);
