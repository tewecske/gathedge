-- One row per achievement tier an account unlocked.
--
-- `code` names an entry of `Achievements.all` in `shared`; `tier` counts from 1. One row per tier, not one row per
-- achievement, keeps the date each tier was reached, and makes the XP of a later feature a plain sum over the rows.
--
-- The primary key is the uniqueness rule: an account unlocks each tier of each achievement once. An evaluation that
-- runs twice (two finished plays at the same moment) inserts with `ON CONFLICT DO NOTHING`, so no row is written twice.
--
-- `user_id` cascades, like `user_streaks` (V29): nothing here has to outlive the account. The key starts with
-- `user_id`, so it also serves the one read, "every row of this account".
CREATE TABLE user_achievements (
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code        VARCHAR(64) NOT NULL,
    tier        INTEGER NOT NULL,
    unlocked_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, code, tier)
);
