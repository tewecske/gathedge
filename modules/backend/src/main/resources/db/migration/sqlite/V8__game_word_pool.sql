-- The SQLite mirror of postgresql/V8__game_word_pool.sql. See that file for what `randomize_each_play` and
-- `game_word_pool` are for.
--
-- As everywhere else in this schema no foreign key is actually enforced (nothing enables
-- `PRAGMA foreign_keys`), so the cascade below is exercised only by PostgresIntegrationSpec.
ALTER TABLE games ADD COLUMN randomize_each_play BOOLEAN NOT NULL DEFAULT 1;

CREATE TABLE game_word_pool (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    game_id             INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    word_id             INTEGER NOT NULL REFERENCES words(id),
    translation_word_id INTEGER NOT NULL REFERENCES words(id)
);

CREATE INDEX idx_game_word_pool_game ON game_word_pool(game_id);
