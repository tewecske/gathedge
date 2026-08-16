-- The SQLite mirror of postgresql/V6__games.sql. See that file for what each table is for, why
-- `slug` and `name` are separate columns, why `score`/`max_score`/`word_count` are denormalized onto
-- `game_plays`, and why `game_play_answers.word_id`/`translation_word_id` do not cascade from `words`.
--
-- As everywhere else in this schema no foreign key is actually enforced (nothing enables
-- `PRAGMA foreign_keys`), so the cascades below are exercised only by PostgresIntegrationSpec.
CREATE TABLE games (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    slug             TEXT NOT NULL UNIQUE,
    name             TEXT NOT NULL,
    source_language  TEXT NOT NULL,
    target_language  TEXT NOT NULL,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL
);

CREATE INDEX idx_games_owner ON games(owner_user_id);

CREATE TABLE game_tags (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    game_id    INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    tag_id     INTEGER NOT NULL REFERENCES tags(id)  ON DELETE CASCADE,
    UNIQUE (game_id, tag_id)
);

CREATE INDEX idx_game_tags_tag ON game_tags(tag_id);

CREATE TABLE game_plays (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    game_id        INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    player_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    score          INTEGER NOT NULL DEFAULT 0,
    max_score      INTEGER NOT NULL,
    word_count     INTEGER NOT NULL,
    started_at     INTEGER NOT NULL,
    finished_at    INTEGER
);

CREATE INDEX idx_game_plays_game ON game_plays(game_id);
CREATE INDEX idx_game_plays_player ON game_plays(player_user_id, started_at);

CREATE TABLE game_play_answers (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    play_id             INTEGER NOT NULL REFERENCES game_plays(id) ON DELETE CASCADE,
    word_id             INTEGER NOT NULL REFERENCES words(id),
    translation_word_id INTEGER NOT NULL REFERENCES words(id),
    position            INTEGER NOT NULL,
    user_answer         TEXT NOT NULL,
    outcome             TEXT NOT NULL,
    points              INTEGER NOT NULL,
    answered_at         INTEGER NOT NULL,
    UNIQUE (play_id, position)
);
