-- The SQLite mirror of postgresql/V7__game_word_limit.sql. See that file for what `word_limit` and
-- `game_play_words` are for, and why `game_play_words`' two word references do not cascade from `words`.
--
-- As everywhere else in this schema no foreign key is actually enforced (nothing enables
-- `PRAGMA foreign_keys`), so the cascade below is exercised only by PostgresIntegrationSpec.
ALTER TABLE games ADD COLUMN word_limit INTEGER;

CREATE TABLE game_play_words (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    play_id             INTEGER NOT NULL REFERENCES game_plays(id) ON DELETE CASCADE,
    word_id             INTEGER NOT NULL REFERENCES words(id),
    translation_word_id INTEGER NOT NULL REFERENCES words(id)
);

CREATE INDEX idx_game_play_words_play ON game_play_words(play_id);
