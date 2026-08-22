-- Moves per-play settings (direction, word count, definite-article display, and which word-preference
-- sampled the play) from `games` (fixed once at creation) onto `game_plays` (chosen fresh at every
-- play) — see the "game variants redesign" design doc. `games` keeps only what genuinely never
-- changes after creation: its language pair, its tags, and `track_results`.

-- New per-play columns, left nullable like `games.word_limit` always was (enforced by the app, not a
-- NOT NULL constraint) rather than the SQLite table-rebuild a later NOT NULL would need — see this
-- migration's SQLite mirror.
ALTER TABLE game_plays ADD COLUMN source_language VARCHAR(8);
ALTER TABLE game_plays ADD COLUMN target_language VARCHAR(8);
ALTER TABLE game_plays ADD COLUMN word_limit INTEGER;
ALTER TABLE game_plays ADD COLUMN include_definite_articles BOOLEAN;
ALTER TABLE game_plays ADD COLUMN word_preference VARCHAR(16) NOT NULL DEFAULT 'all';

-- Every play that predates this migration ran under its game's own (then immutable) settings, so a
-- correlated-subquery backfill from `games` reports exactly what that play actually used. Written as a
-- correlated subquery per column, not `UPDATE ... FROM`, so the same statement works unchanged on both
-- dialects.
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

-- The fixed-pool/reshuffle mechanism this migration retires: every play now samples fresh at
-- `startPlay` (see `GameService`), so a game's own frozen draw has nothing left to read it.
DROP TABLE game_word_pool;

-- The three columns that moved onto `game_plays` above. `word_limit`/`include_definite_articles` are
-- superseded by the per-play columns just added; `randomize_each_play` has no per-play equivalent at
-- all — reshuffle/fixed-pool no longer exists as a concept.
ALTER TABLE games DROP COLUMN word_limit;
ALTER TABLE games DROP COLUMN randomize_each_play;
ALTER TABLE games DROP COLUMN include_definite_articles;
