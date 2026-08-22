# Game variants redesign

## Goal

Split today's single "game" concept into two layers:

- **Base game** (owner, set once at creation, immutable): source language, target language, tags
  (which decide the word pool), and whether plays are tracked.
- **Play variant** (player, chosen fresh every time they start a play): direction (swap
  source/target), how many words, whether German definite articles are shown, and which word-pool
  preference to sample from (all words / unplayed words / most-mistaken words).

Today, `wordLimit`, `randomizeEachPlay`, `includeDefiniteArticles` are all baked into the game row
at creation time and never change. This redesign moves them to play-start time, drops the
`randomizeEachPlay = false` fixed-pool/reshuffle mechanism entirely (every play always samples
fresh), and adds two new preference-based sampling options driven by the player's own play history
in this game.

## Base game (unchanged surface, smaller)

`CreateGameRequest` / `GameDetail` keep only: `sourceLanguage`, `targetLanguage`, `tagIds` /
`tagNames`, `trackResults`. `wordLimit`, `randomizeEachPlay`, `includeDefiniteArticles` are removed
from both. `GameService.createGame` no longer takes or stores them. The owner-facing `GameSetupPage`
shrinks to: language pair, tag checkboxes, track-results toggle. No word-count, randomize, or
articles controls at creation time — those move to the play-variant picker.

`reshuffle` is removed: the endpoint, `GameService.reshuffle`, `GameFailure.NotFixedPool`, the
`game_word_pool` table, and `GameRepository.wordPoolOf`/`replaceGameWordPool`. A game's eligible pool
is always computed live from its tags at play-start time, the same as today's
`randomizeEachPlay = true` path.

## Play variant (new)

`POST /api/games/{slug}/plays` gains a request body, `StartPlayRequest`:

```scala
final case class StartPlayRequest(
  swapDirection: Boolean = false,
  wordLimit: Option[Int] = None,
  includeDefiniteArticles: Boolean = true,
  wordFilter: WordFilter = WordFilter.All,
) derives JsonCodec

enum WordFilter derives JsonCodec {
  case All, Unplayed, MostMistakes
}
```

- **`swapDirection`**: `true` plays the game's `targetLanguage` → `sourceLanguage` instead of the
  stored direction. Fails `badRequest` (reuses `NoEligibleWords`) if the reverse direction's pool is
  empty for this game's tags.
- **`wordLimit`**: same semantics as today's game-level field, just supplied per play instead of
  fixed at creation — `None` = every eligible word (in the resolved direction), `Some(n)` = sample
  `n` (or the whole pool if smaller).
- **`includeDefiniteArticles`**: same effect as today's game-level field (gates
  `Word.displayText` at every call site `GameService` already funnels through), just supplied per
  play. Meaningless/ignored when neither resolved direction is German, same as today's UI-hide rule.
- **`wordFilter`**: only affects *which* words get sampled when `wordLimit` narrows the pool; see
  below. No effect when `wordLimit` is `None` (every eligible word plays regardless of preference).

### Word filter semantics — priority sampling, not a hard filter

`Unplayed` and `MostMistakes` never shrink the playable pool by themselves. They only change *sampling
order* when `wordLimit = Some(n)` and `n < pool.size`:

1. Compute this player's per-word answer history **for this game, in the resolved direction only**
   (a word answered DE→HU does not count toward the HU→DE history for the same word pair — tracked
   separately per direction, per the direction-scope decision below).
2. Build a preferred-first ordering of the eligible pool:
   - `Unplayed`: words with zero answers by this player (this game, this direction) sorted first,
     then already-played words.
   - `MostMistakes`: words sorted by this player's wrong-answer count (this game, this direction),
     descending, ties broken arbitrarily; words with zero answers count as zero mistakes and sort
     after any word with at least one recorded mistake.
   - `All`: no reordering — today's uniform random sample.
3. Take the first `n` from that ordering (for `Unplayed`/`MostMistakes`, this means: fill from the
   preferred subset first, then top up from the rest of the pool if the preferred subset is smaller
   than `n`).

"This player's answer history" reads `game_play_answers` joined through `game_plays` filtered to
`game_id = this game`, `player_user_id = this player`, and the word pairs matching the resolved
direction (`word_id`/`translation_word_id` matching the direction the answer was originally recorded
in — see the `game_plays` variant snapshot below, which is what lets this be computed without
re-deriving direction from `games`' now-mutable-per-play meaning).

### Direction scope for history

Confirmed: a word answered in one direction and the same word pair answered in the reverse direction
are tracked as **separate** history for filtering purposes. `MostMistakes`/`Unplayed` are computed
per `(game, player, direction)`, not merged across both directions of the same word pair.

## Variant tracking on `game_plays`

Per the mid-brainstorm addendum: play tracking must record the variant settings a play actually
used, not just the base game's settings. `game_plays` gains:

- `source_language`, `target_language` (the resolved direction for this specific play — may be the
  game's stored pair reversed)
- `word_limit` (nullable `INTEGER`, mirrors the old `games.word_limit` semantics but per play)
- `include_definite_articles` (`BOOLEAN`)
- `word_filter` (`VARCHAR`, one of `WordFilter`'s codes)

The same variant settings played again by the same or a different player simply produces another
`game_plays` row carrying the identical variant columns — there is no separate "variant" entity, no
dedup, and no grouped/aggregate view. `GamePlaySummary`, `GamePlayDetail`, and `MyPlaySummary` (and
`GameResults`, the player's own result screen) each gain a `variant: GameVariantDto` field:

```scala
final case class GameVariantDto(
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  wordLimit: Option[Int],
  includeDefiniteArticles: Boolean,
  wordFilter: WordFilter,
) derives JsonCodec
```

so the owner's per-play listing, the owner's per-play detail modal, the player's own result screen,
and "my plays across every game" all show which variant a given play used.

## New endpoint: play-setup preview

The play-variant picker needs an honest "N of M eligible" count once a direction/filter is chosen,
the same role `GET /api/games/setup/words` plays for the creation screen. New endpoint:

```
GET /api/games/{slug}/plays/setup?swapDirection=&wordFilter=
```

Requires a session (like every play action) since the `Unplayed`/`MostMistakes` counts depend on the
calling player's own history. Answers the resolved-direction eligible pool, in preference order (same
ordering `startPlay` would sample from), as `List[GameSetupWord]` — reused as-is; the picker shows
"N eligible" and, for the two preference filters, can preview which words would be prioritized.

## Frontend

**`GameSetupPage`** (owner, creation): drops the word-limit controls, randomize radios, and
articles toggle. Keeps language-pair selects, tag checkboxes + filter, track-results toggle, words
preview list. Much shorter form.

**`GameInstancePage`** (share/play landing): today this page's "Play" button starts a play directly
with no options. It gains a variant picker directly above the Play button:

- **Direction**: `[source language] ⇄ [target language]` — plain labels either side of a swap
  icon/arrow, no dropdowns. Clicking the arrow swaps the pair for this play. Disabled/no-op if the
  reverse direction's pool is empty (mirrors `swapDirection`'s `badRequest` case being unreachable
  from the UI).
- **Word count**: same select-all-vs-number control moved verbatim from the old `GameSetupPage`.
- **Include articles**: same toggle moved verbatim, shown only when German is in either direction of
  the current pair.
- **Word filter**: a three-way choice (`All` / `Unplayed` / `Most mistakes`), each refetching the
  play-setup preview to update the "N eligible" count and preview list.

`GameApiClient.create` sheds the four dropped parameters; `GameApiClient.startPlay` gains a body
matching `StartPlayRequest`; a new `GameApiClient.playSetup` call backs the preview.

## Data model / migration

New Flyway migration (both `postgresql` and `sqlite` dialects), after `V13__word_search_norm.sql`:

- `games`: drop `word_limit`, `randomize_each_play`, `include_definite_articles`.
- Drop `game_word_pool` entirely.
- `game_plays`: add `source_language`, `target_language`, `word_limit`, `include_definite_articles`,
  `word_filter`. Backfill the four from the owning `games` row (`source_language`/`target_language`/
  `word_limit`/`include_definite_articles` as they stood before being dropped, `word_filter` defaulted
  to `'all'`) so existing plays report the settings they actually ran under, then make the
  non-nullable ones `NOT NULL`.

SQLite specifics (dropping a plain, unconstrained column is supported since SQLite 3.35 and none of
`word_limit`/`randomize_each_play`/`include_definite_articles` carry a `UNIQUE` or `CHECK`
constraint) are an implementation detail for the plan/implementation phase, not a design decision —
flag it there if the project's bundled SQLite predates 3.35.

## Testing

- `GameServiceSpec`: replace fixed-pool/reshuffle tests with play-variant tests — swap direction,
  word-limit sampling per play, filter priority ordering (`Unplayed`, `MostMistakes`) including the
  "preferred subset smaller than requested count" top-up case, and per-direction history isolation.
- `GameRepository`/`PostgresIntegrationSpec`: the new `game_plays` columns and the dropped
  `game_word_pool` table/`games` columns, per this repo's rule that schema changes touching `users`-
  adjacent cascades get a Postgres regression test — `game_plays` already cascades from `users`, so
  the new columns don't add a new FK, but the migration itself (drop + backfill) should get a
  Postgres-dialect test.
- `ApiEndpointsSpec`/`OpenApiSpec`: updated codec lists and status tables for `create`,
  `startPlay` (now has a body + `badRequest`), the new `plays/setup` endpoint, and every DTO gaining
  `variant`.
- Frontend: `GameSetupPageSpec` trimmed to the smaller form; a new spec for the play-variant picker
  (swap arrow, filter selection, preview refetch); `GamePlayQuerySpec` unaffected (owner listing
  query shape, not the new variant column).
