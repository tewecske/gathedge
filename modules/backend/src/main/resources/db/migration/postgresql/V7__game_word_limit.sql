-- Lets a quiz draw a fixed-size sample from its eligible pool instead of always using every eligible
-- word. Two pieces: `games.word_limit`, the creator's choice, and `game_play_words`, the specific
-- sample a play actually got, fixed at `startPlay` and never touched again.
--
-- Before this migration a play's word set was never stored anywhere: `GameService.eligibleWordPool`
-- recomputed the game's whole live eligible pool, fresh, on every call to `nextPrompt`/`submitAnswer`/
-- `getResults`, which was safe only because that pool *was* the play's word set in its entirety — there
-- was no narrower subset to lose track of. A word limit breaks that: once a play only covers N of a
-- larger pool, "which N" has to be decided once and then stay fixed, because (a) there is no stable order
-- to re-derive the same N words on a later call, and (b) a word already answered must remain part of the
-- play's set even if it would no longer be among a freshly-redrawn N, or `remaining = pool - answered`
-- stops matching `word_count`. Hence a new table rather than a recomputation rule.

-- NULL means "use every eligible word" — today's only behaviour, and the default a game that predates
-- this column keeps. A positive integer means "sample exactly this many from the eligible pool when a
-- play starts" (fewer, if the pool itself is smaller — see `GameService.startPlay`). Enforced positive by
-- `Validation.validateWordLimit`, not a CHECK constraint, for the same reason `games.name`'s blank check
-- lives in application code: the message has to be a `MessageRef`, not a raw constraint-violation 500.
ALTER TABLE games ADD COLUMN word_limit INTEGER;

-- The word pairs actually in play for one specific attempt — written once, in the same transaction as
-- the `game_plays` insert, and read back by every later call in that play instead of any of them
-- recomputing a pool. For a `word_limit IS NULL` game this ends up holding the entire eligible pool the
-- moment the play started, which is deliberately the same shape a `word_limit` game gets: `nextPrompt`/
-- `submitAnswer`/`getResults` never need to know which case they are in.
--
-- `word_id` and `translation_word_id` deliberately do NOT cascade from `words`, the same choice
-- `game_play_answers` makes and for the same reason: this table records what a play *started* with, not
-- current dictionary state, and a play's word count must never silently change because a dictionary row
-- was later removed. It is closer to that permanent-history table than to `game_tags`, even though
-- nothing here is a score — the set itself is fixed the moment the play begins and is never re-derived,
-- which is exactly `game_play_answers`' reasoning, not `game_tags`' (a live join that changes as tags
-- change). As with `game_play_answers`, no code path deletes a `words` row today, so Postgres' implicit
-- `NO ACTION` only matters once a "delete word" feature exists, at which point it will force that feature
-- to deal explicitly with words holding quiz history.
CREATE TABLE game_play_words (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    play_id             BIGINT NOT NULL REFERENCES game_plays(id) ON DELETE CASCADE,
    word_id             BIGINT NOT NULL REFERENCES words(id),
    translation_word_id BIGINT NOT NULL REFERENCES words(id)
);

-- The only query this table answers is "this play's word pairs" (`GameRepository.wordPairsOf`), so one
-- index on `play_id` is all it needs — the same one-index reasoning `game_play_answers`' doc comment
-- gives, minus that table's `position` ordering, since this table has no per-row order of its own.
CREATE INDEX idx_game_play_words_play ON game_play_words(play_id);
