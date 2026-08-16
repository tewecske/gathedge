-- Lets a game's word_limit sample be drawn once, at creation (or on demand, via reshuffle), and reused by
-- every playthrough instead of `startPlay` redrawing a fresh subset each time — see `GameService.startPlay`'s
-- doc comment on why the two modes exist and how a reader chooses between them on the setup screen.

-- `TRUE` (the default, and what every existing row keeps) is today's only behaviour: `startPlay` draws a
-- fresh sample from the live eligible pool on every call. `FALSE` means the game's `game_word_pool` rows
-- are the fixed sample every play uses instead, resampled only by `GameService.reshuffle`.
ALTER TABLE games ADD COLUMN randomize_each_play BOOLEAN NOT NULL DEFAULT TRUE;

-- A game's own fixed draw — the `randomize_each_play = FALSE` counterpart to `game_play_words`, but keyed
-- by the game rather than one play of it, since every play of a fixed game reads the same set. Written once
-- at `createGame` (when the reader picked "randomize now") and replaced wholesale by `reshuffle`; a
-- `randomize_each_play = TRUE` game keeps no rows here at all, since `startPlay` never reads this table for
-- one. Same non-cascading `word_id`/`translation_word_id` reasoning as `game_play_words`: this is the game's
-- own frozen draw, not a live join, so a later dictionary change must not silently shrink it.
CREATE TABLE game_word_pool (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id             BIGINT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    word_id             BIGINT NOT NULL REFERENCES words(id),
    translation_word_id BIGINT NOT NULL REFERENCES words(id)
);

-- The only query this table answers is "this game's fixed word pairs" (`GameRepository.wordPoolOf`), the
-- same one-index reasoning `game_play_words`' doc comment gives for its own `play_id` index.
CREATE INDEX idx_game_word_pool_game ON game_word_pool(game_id);
