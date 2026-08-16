-- The games feature: a tag-scoped vocabulary quiz, its per-tag eligibility, and the scored history
-- of every attempt at it.
--
-- The two V1__init.sql conventions hold here too: every timestamp is epoch millis in a BIGINT, and a
-- reference to `users` cascades unless the row is a record that must outlive the account it names.
-- `owner_user_id` and `player_user_id` both cascade — a game and a person's attempts at any game are
-- personal data in the same sense a tag or a guest_claim_code is, not an administrative record like
-- `audit_log` or `usage_events`, so there is nothing here to preserve once the account is gone.
--
-- `word_id` and `translation_word_id` on `game_play_answers` are the one deliberate exception: see
-- that table's comment for why they do NOT cascade from `words`, unlike every other table in this
-- schema that references it.

-- One quiz, built from a game's own set of tags (see `game_tags`) and scoped to a language pair. The
-- source/target split mirrors `word_translations`' directionality: a game asks for `target_language`
-- words given their `source_language` prompt (or vice versa — which way round is a play-time choice,
-- not a property of the game).
--
-- `slug` and `name` are deliberately different columns with different mutability, not one renameable
-- field: `slug` is the permanent key a share link is built from — generated once at creation and
-- never touched again, so a link handed out today still resolves after the owner renames the game —
-- while `name` is cosmetic and free to edit at will. Sized and typed like `password_reset_tokens.token`
-- / `guest_claim_codes.code`: an app-generated random identifier that must never collide, hence UNIQUE
-- rather than relying on `id`, which would leak how many games exist and in what order.
CREATE TABLE games (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    slug             VARCHAR(64)  NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    source_language  VARCHAR(8)   NOT NULL,                   -- 'en' | 'de' | 'hu'
    target_language  VARCHAR(8)   NOT NULL,                   -- 'en' | 'de' | 'hu'
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL                           -- bumped by the app on every rename; no DB-side trigger
);

-- "My games" is the only listing this table needs to answer directly; `slug`'s own UNIQUE index
-- already answers the share-link lookup.
CREATE INDEX idx_games_owner ON games(owner_user_id);

-- Which tags a game draws its words from. A game can span several tags — someone building a "kitchen
-- + travel" quiz mixes both — so this is a join table exactly like `word_tags`, not a single column on
-- `games`.
--
-- `tag_id` is the only reference to a per-account row, so this table reaches `users` only through
-- `tags` and cascades with it, same as `word_tag_pairs`.
CREATE TABLE game_tags (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id    BIGINT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    tag_id     BIGINT NOT NULL REFERENCES tags(id)  ON DELETE CASCADE,
    UNIQUE (game_id, tag_id)
);

-- The UNIQUE index above already answers "every tag on this game" as a leftmost prefix, the same as
-- `word_tag_pairs`. This is the other direction, mirroring `idx_word_tags_tag`.
CREATE INDEX idx_game_tags_tag ON game_tags(tag_id);

-- One attempt at a game, by one account. `score`, `max_score` and `word_count` are denormalized onto
-- this row rather than derived from `game_play_answers` on every read, because a play is read far more
-- often than it is written to (a progress bar, a result screen, a leaderboard) and each of the three
-- is cheap to maintain incrementally as answers come in, unlike a SUM/COUNT over every answer row each
-- time. `game_play_answers` remains the source of truth those three are kept in sync with; nothing here
-- recomputes them.
--
-- `word_count` and `max_score` are fixed at the moment a play starts — how many word pairs it will
-- cover, and the ceiling score if every one of them is answered correctly — neither depends on what the
-- player actually does. `score` is the one column that changes as the play progresses, and starts at 0.
--
-- `finished_at` is NULL for a play still in progress and set once when it completes; there is no
-- separate "abandoned" state; an unfinished play is just one whose `finished_at` never gets set.
CREATE TABLE game_plays (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id        BIGINT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    player_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    score          INTEGER NOT NULL DEFAULT 0,
    max_score      INTEGER NOT NULL,
    word_count     INTEGER NOT NULL,
    started_at     BIGINT NOT NULL,
    finished_at    BIGINT
);

-- A game's own history — a leaderboard, or "how many times has this been played" — reads by
-- `game_id`.
CREATE INDEX idx_game_plays_game ON game_plays(game_id);
-- "My plays", most recent first, is the other access pattern; the trailing `started_at` makes that
-- ordering an index scan rather than a sort, the same shape as `idx_login_attempts_email`.
CREATE INDEX idx_game_plays_player ON game_plays(player_user_id, started_at);

-- One word pair asked and answered inside one play — the per-word-pair progression record this
-- feature is built on. There is no separate stats table: a play's own `score`/`max_score`/`word_count`
-- are the per-play summary, and a later feature aggregates this table across plays for anything more.
--
-- Both `word_id` and `translation_word_id` are stored, not just one, for the same reason
-- `word_tag_pairs` stores both: a word usually has several translations, and this row has to say which
-- specific sense was being tested, not merely which word was on screen.
--
-- These two columns deliberately do NOT cascade from `words`, unlike every other reference to it in
-- this schema (`word_translations`, `word_tag_pairs`). Those two tables describe current state — a
-- translation edge, a lesson's word list — which makes sense to disappear along with a word that is
-- gone. This table is a permanent scored history record instead: a play's `score` must never silently
-- change after the fact because a dictionary row was later removed. There is also no code path that
-- deletes a `words` row today (`WordRepository` never issues a `DELETE FROM words`), so in practice this
-- default (Postgres' implicit `NO ACTION`) only matters if a "delete word" feature is ever added — at
-- which point it will force that feature to deal explicitly with words that have quiz history, rather
-- than one silently corrupting the other.
CREATE TABLE game_play_answers (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    play_id             BIGINT NOT NULL REFERENCES game_plays(id) ON DELETE CASCADE,
    word_id             BIGINT NOT NULL REFERENCES words(id),
    translation_word_id BIGINT NOT NULL REFERENCES words(id),
    position            INTEGER NOT NULL,
    user_answer         VARCHAR(255) NOT NULL,
    outcome             VARCHAR(16)  NOT NULL,                 -- 'correct' | 'typo' | 'wrong'
    points              INTEGER NOT NULL,
    answered_at         BIGINT NOT NULL,
    UNIQUE (play_id, position)
);

-- No separate index needed for "answers in this play, in order": the UNIQUE index above is already a
-- (play_id, position)-sorted B-tree, so it answers that range scan directly — same reasoning as
-- `word_tag_pairs`' comment on why it has only one index, not two.
