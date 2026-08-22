-- The SQLite mirror of postgresql/V14__game_play_variants.sql. See that file for the reasoning behind
-- moving direction/`word_limit`/`include_definite_articles` onto `game_plays`, retiring
-- `randomize_each_play`/`game_word_pool`, and the correlated-subquery backfill shape (kept identical
-- across both dialects rather than using SQLite's newer `UPDATE ... FROM`).
--
-- `DROP COLUMN` needs SQLite 3.35+ for an unconstrained plain column, which this project's bundled
-- `sqlite-jdbc` (3.53.2.0) comfortably clears.
ALTER TABLE game_plays ADD COLUMN source_language TEXT;
ALTER TABLE game_plays ADD COLUMN target_language TEXT;
ALTER TABLE game_plays ADD COLUMN word_limit INTEGER;
ALTER TABLE game_plays ADD COLUMN include_definite_articles INTEGER;
ALTER TABLE game_plays ADD COLUMN word_preference TEXT NOT NULL DEFAULT 'all';

UPDATE game_plays SET source_language = (
  SELECT source_language FROM games WHERE games.id = game_plays.game_id
);
UPDATE game_plays SET target_language = (
  SELECT target_language FROM games WHERE games.id = game_plays.game_id
);
UPDATE game_plays SET word_limit = (
  SELECT word_limit FROM games WHERE games.id = game_plays.game_id
);
UPDATE game_plays SET include_definite_articles = (
  SELECT include_definite_articles FROM games WHERE games.id = game_plays.game_id
);

DROP TABLE game_word_pool;

ALTER TABLE games DROP COLUMN word_limit;
ALTER TABLE games DROP COLUMN randomize_each_play;
ALTER TABLE games DROP COLUMN include_definite_articles;
