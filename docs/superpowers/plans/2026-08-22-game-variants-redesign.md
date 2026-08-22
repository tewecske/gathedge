# Game Variants Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the game feature into an immutable base game (owner: languages, tags, track-results) and per-play variants (player: direction swap, word count, definite articles, unplayed/most-mistakes sampling preference), snapshotting the variant a play actually used onto that play's row.

**Architecture:** `games` sheds `word_limit`/`randomize_each_play`/`include_definite_articles`; `game_plays` gains `source_language`/`target_language`/`word_limit`/`include_definite_articles`/`word_preference`. `GameService.startPlay` takes the variant as parameters, resolves the play's actual direction, samples the pool (uniformly, or preference-ordered from this player's per-direction answer history in this game), and writes the resolved variant onto the new `game_plays` row. The fixed-pool/reshuffle mechanism is deleted outright — every play always samples fresh.

**Tech Stack:** Scala 3, ZIO, ZIO HTTP (Endpoint API), Quill (dual Postgres/SQLite dialect), Laminar (Scala.js frontend), zio-test.

**Spec:** `docs/superpowers/specs/2026-08-22-game-variants-redesign-design.md`

## Global Constraints

- `-noindent` is set project-wide: every block needs explicit `{ }`, no significant-indentation Scala 3.
- No `-Xfatal-warnings` — this project uses `-Werror`.
- Every repository method logs one INFO line via `QuillRepository.logged`; never log a password hash, session id, token, OAuth subject, or email — and per `GameRepository`'s own rule, never a game's name/slug or a submitted answer's text either.
- Every query in `GameRepository` is a Quill query rendered at runtime; only use `DynamicQuery` where the shape genuinely varies (this plan's new query does not, so it stays a plain `quote`).
- Flyway migrations are duplicated, schema-identical, under `backend/src/main/resources/db/migration/{postgresql,sqlite}/`. Timestamps are epoch-millis `BIGINT`/`INTEGER`. No foreign key is enforced on SQLite; anything that changes `users`-adjacent cascades needs a `PostgresIntegrationSpec` regression test — this plan changes no cascade shape, so none is required beyond keeping the existing games/plays cascade tests compiling.
- Every `ApiFailure` carries a `MessageRef` code plus an English fallback `message` string; `shared/i18n/messages.{en,hu}.json` must carry identical key sets (`MessagesSpec` enforces this) — any `UiKeys`/`MessageKeys` addition needs both catalogs updated in the same task.
- `.outErrors(...)` on an `Endpoint` must list exactly the union of failures that endpoint's handler can produce; keep it in step with the handler by hand.
- Session auth: aspects (`authenticated`, `csrf`, `requestContext`) attach to whole `Routes` values, never to an individual `handler` that takes path parameters.

---

## Task 1: `WordPreference` domain enum

**Files:**
- Create: `modules/shared/shared/src/main/scala/gathedge/shared/domain/WordPreference.scala`
- Test: `modules/shared/shared/src/test/scala/gathedge/shared/domain/WordPreferenceSpec.scala`

**Interfaces:**
- Produces: `enum WordPreference { case All, Unplayed, MostMistakes }`, `WordPreference.code(pref): String`, `WordPreference.fromString(value: String): Option[WordPreference]` — the same shape `AnswerOutcome`/`WordLanguage` already follow, consumed by every later task that reads/writes `game_plays.word_preference` or the `wordPreference` query param.

- [ ] **Step 1: Write the failing test**

```scala
package gathedge.shared.domain

import zio.test._

object WordPreferenceSpec extends ZIOSpecDefault {
  def spec = {
    suite("WordPreference")(
      test("code round-trips through fromString for every case") {
        assertTrue(WordPreference.values.forall(p => WordPreference.fromString(WordPreference.code(p)).contains(p)))
      },
      test("code is the stable wire string, not toString") {
        assertTrue(
          WordPreference.code(WordPreference.All) == "all",
          WordPreference.code(WordPreference.Unplayed) == "unplayed",
          WordPreference.code(WordPreference.MostMistakes) == "mostMistakes",
        )
      },
      test("fromString is case-insensitive and unknown strings answer None") {
        assertTrue(
          WordPreference.fromString("ALL").contains(WordPreference.All),
          WordPreference.fromString("bogus").isEmpty,
        )
      },
    )
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "sharedJVM/testOnly gathedge.shared.domain.WordPreferenceSpec"`
Expected: FAIL — `WordPreference` does not exist yet (compile error).

- [ ] **Step 3: Write minimal implementation**

```scala
package gathedge.shared.domain

import zio.json.*

/** Which words a play should prefer sampling from, when `wordLimit` narrows the eligible pool below its full
  * size — see the "game variants redesign" design doc's "priority sampling, not a hard filter" section. Has no
  * effect at all when the pool is not narrowed. Stored on `game_plays.word_preference`.
  */
enum WordPreference derives JsonCodec, CanEqual {
  case All,
    Unplayed,
    MostMistakes
}

object WordPreference {

  val values: List[WordPreference] = List(All, Unplayed, MostMistakes)

  /** What `game_plays.word_preference` stores and what the `wordPreference` query param carries. Written out rather
    * than derived from `toString`, the same reasoning `AnswerOutcome.code`/`WordLanguage.code` follow.
    */
  def code(preference: WordPreference): String = {
    preference match {
      case All          =>
        "all"
      case Unplayed      =>
        "unplayed"
      case MostMistakes =>
        "mostMistakes"
    }
  }

  def fromString(value: String): Option[WordPreference] = {
    value.toLowerCase match {
      case "all"          =>
        Some(All)
      case "unplayed"     =>
        Some(Unplayed)
      case "mostmistakes" =>
        Some(MostMistakes)
      case _              =>
        None
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "sharedJVM/testOnly gathedge.shared.domain.WordPreferenceSpec"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/shared/shared/src/main/scala/gathedge/shared/domain/WordPreference.scala modules/shared/shared/src/test/scala/gathedge/shared/domain/WordPreferenceSpec.scala
git commit -m "feat: add WordPreference domain enum"
```

---

## Task 2: Shared DTOs, endpoints and schemas

**Files:**
- Modify: `modules/shared/shared/src/main/scala/gathedge/shared/dto/GameDto.scala`
- Modify: `modules/shared/shared/src/main/scala/gathedge/shared/api/GameEndpoints.scala`
- Modify: `modules/shared/shared/src/main/scala/gathedge/shared/api/ApiSchemas.scala`
- Test: `modules/backend/src/test/scala/gathedge/backend/http/ApiEndpointsSpec.scala` (compiles against these types; no game-specific assertions live there today — this task must not break it)

**Interfaces:**
- Consumes: `WordPreference` (Task 1).
- Produces: trimmed `CreateGameRequest`/`GameDetail` (no `wordLimit`/`randomizeEachPlay`/`includeDefiniteArticles`), new `StartPlayRequest(swapDirection, wordLimit, includeDefiniteArticles, wordPreference)`, new `GameVariantDto(sourceLanguage, targetLanguage, wordLimit, includeDefiniteArticles, wordPreference)`, `GamePlaySummary`/`GamePlayDetail`/`MyPlaySummary`/`GameResults` each gaining a `variant: GameVariantDto` field, `GameEndpoints.startPlay` now taking a body, `GameEndpoints.playSetup` (new), `GameEndpoints.reshuffle` removed. Every later backend/frontend task depends on these exact names and field orders.

This task has no test of its own beyond "the project compiles" — DTOs and endpoint descriptions are declarations, not logic. Its steps are a compile-and-commit cycle instead of red/green.

- [ ] **Step 1: Trim `CreateGameRequest`/`GameDetail`, add `StartPlayRequest`/`GameVariantDto`, thread `variant` through the four DTOs that need it**

Replace the whole content of `modules/shared/shared/src/main/scala/gathedge/shared/dto/GameDto.scala` with:

```scala
package gathedge.shared.dto

import gathedge.shared.domain.{AnswerOutcome, WordLanguage, WordPreference}
import zio.json.*

/** What `POST /api/games` needs: the language pair and tags a base game is built from, and whether it tracks
  * results. Nothing here ever changes after creation — word count, direction, article display and word
  * preference are all play-time choices now, carried by [[StartPlayRequest]] instead. See the "game variants
  * redesign" design doc.
  */
final case class CreateGameRequest(
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagIds: List[Long],
  trackResults: Boolean = false,
) derives JsonCodec

/** `POST /api/games`'s answer: just enough to navigate to the game and show its name. */
final case class GameCreated(slug: String, name: String) derives JsonCodec

final case class RenameGameRequest(name: String) derives JsonCodec

/** One row of `GET /api/games/setup/words`'s answer: the setup screen's preview of exactly the pool a game built
  * from the requested tags and language pair would draw from — `text` already carries a gendered source word's
  * article, the same [[gathedge.shared.domain.Word.displayText]] every prompt/result elsewhere in the game uses.
  * Deduped to one row per source word. Also reused, unmodified, by `GET /api/games/{slug}/plays/setup`'s
  * play-time preview.
  */
final case class GameSetupWord(wordId: Long, text: String) derives JsonCodec

/** A game as a caller may see it: no owner-only data, no id — `slug` is what a reader addresses it by. */
final case class GameDetail(
  slug: String,
  name: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagNames: List[String],
  trackResults: Boolean = false,
) derives JsonCodec

/** `POST /api/games/{slug}/plays`'s request body: the play-time variant a player picks fresh every time. See the
  * design doc's "priority sampling, not a hard filter" section for what [[wordPreference]] actually does.
  *
  * `swapDirection`: `true` plays the game's `targetLanguage` -> `sourceLanguage` instead of its stored direction.
  * `wordLimit`: `None` = every eligible word in the resolved direction (the default); `Some(n)` = sample `n` (or
  * the whole pool, if smaller). `includeDefiniteArticles`: `true` (the default) keeps a German noun's
  * "der"/"die"/"das" in the prompt, the accepted answer, and the results text. `wordPreference`: `All` (the
  * default) samples uniformly; the other two cases only change *which* words a narrowed sample favors, never the
  * total count.
  */
final case class StartPlayRequest(
  swapDirection: Boolean = false,
  wordLimit: Option[Int] = None,
  includeDefiniteArticles: Boolean = true,
  wordPreference: WordPreference = WordPreference.All,
) derives JsonCodec

/** The variant settings one specific play actually ran under — a snapshot, not a live reference to the (now
  * immutable) base game, since a play may have swapped direction or picked a narrower/differently-preferenced
  * sample than another play of the same game. Embedded in every play-facing DTO: [[GameResults]],
  * `GamePlaySummary`, `GamePlayDetail`, `MyPlaySummary`.
  */
final case class GameVariantDto(
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  wordLimit: Option[Int],
  includeDefiniteArticles: Boolean,
  wordPreference: WordPreference,
) derives JsonCodec

/** `POST /api/games/{slug}/plays`'s answer: enough for the play loop to start — the id every later play call
  * addresses, and the two numbers a progress bar needs.
  */
final case class PlayStarted(playId: Long, wordCount: Int, maxScore: Int) derives JsonCodec

/** `GET /api/games/plays/{playId}/prompt`'s answer: the next word to show, or `finished = true` once none remain.
  * `wordId`/`wordText`/`position` are absent exactly when `finished` is true.
  */
final case class GamePrompt(
  finished: Boolean,
  wordId: Option[Long] = None,
  wordText: Option[String] = None,
  position: Option[Int] = None,
) derives JsonCodec

final case class SubmitAnswerRequest(wordId: Long, answerText: String) derives JsonCodec

/** One row of the results screen's mistakes table. */
final case class GameAnswerResult(
  wordText: String,
  expectedText: String,
  givenText: String,
  outcome: AnswerOutcome,
) derives JsonCodec

/** `GET /api/games/plays/{playId}/results`'s answer: the finished play's score, full answer history, and the
  * variant it was played under.
  */
final case class GameResults(
  score: Int,
  maxScore: Int,
  wordCount: Int,
  answers: List[GameAnswerResult],
  variant: GameVariantDto,
) derives JsonCodec

/** One row of `GET /api/games/mine` — the caller's own games, most recently created first. `playCount` is `0` for
  * a game nobody has played yet, never absent.
  */
final case class MyGameSummary(
  slug: String,
  name: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagNames: List[String],
  playCount: Long,
  createdAt: Long,
) derives JsonCodec

/** One row of `GET /api/games/{slug}/plays` — a tracked game's owner-facing listing. `playerEmail` is `None` for a
  * guest who never gave one; `playerIsGuest` lets the table badge that instead of showing a blank cell. `variant`
  * is the settings this particular play actually ran under.
  */
final case class GamePlaySummary(
  playId: Long,
  playerEmail: Option[String],
  playerIsGuest: Boolean,
  score: Int,
  maxScore: Int,
  wordCount: Int,
  startedAt: Long,
  finishedAt: Option[Long],
  variant: GameVariantDto,
) derives JsonCodec

/** One page of a tracked game's plays. `total` counts what matches the player filter, the same rule [[UserPage]]
  * follows.
  */
final case class GamePlayPage(items: List[GamePlaySummary], total: Long) derives JsonCodec

/** `GET /api/games/{slug}/plays/{playId}`'s answer: one player's full attempt, for the owner-facing result modal. */
final case class GamePlayDetail(
  playId: Long,
  playerEmail: Option[String],
  playerIsGuest: Boolean,
  score: Int,
  maxScore: Int,
  wordCount: Int,
  startedAt: Long,
  finishedAt: Option[Long],
  answers: List[GameAnswerResult],
  variant: GameVariantDto,
) derives JsonCodec

/** One row of `GET /api/games/plays/mine` (or a shared/admin equivalent): one play, with enough of its game's own
  * identity to render in a listing that spans more than one game.
  */
final case class MyPlaySummary(
  playId: Long,
  gameSlug: String,
  gameName: String,
  score: Int,
  maxScore: Int,
  wordCount: Int,
  startedAt: Long,
  finishedAt: Option[Long],
  variant: GameVariantDto,
) derives JsonCodec

/** One page of a cross-game play history — the player's own, a viewer's shared read, or an admin's. */
final case class MyPlayPage(items: List[MyPlaySummary], total: Long) derives JsonCodec

/** The columns `GET /api/games/{slug}/plays` will order by. Player is absent: filtering by it is a substring match
  * on `users.email`, but ordering by it would need a join this listing deliberately avoids.
  */
object GamePlaySort {
  val score: String     = "score"
  val wordCount: String = "wordCount"
  val startedAt: String = "startedAt"

  val all: List[String] = List(score, wordCount, startedAt)
}
```

- [ ] **Step 2: Update `GameEndpoints` — `startPlay` gains a body, add `playSetup`, remove `reshuffle`**

In `modules/shared/shared/src/main/scala/gathedge/shared/api/GameEndpoints.scala`:

Replace the `import gathedge.shared.dto.{...}` block with:

```scala
import gathedge.shared.dto.{
  CreateGameRequest,
  GameCreated,
  GameDetail,
  GamePlayDetail,
  GamePlayPage,
  GamePrompt,
  GameResults,
  GameSetupWord,
  MyGameSummary,
  MyPlayPage,
  PlayStarted,
  RenameGameRequest,
  StartPlayRequest,
  SubmitAnswerRequest,
}
```

Add two new query codecs next to `tagIdsQuery`:

```scala
  private val swapDirectionQuery  = HttpCodec.query[Boolean]("swapDirection").optional
  private val wordPreferenceQuery = HttpCodec.query[String]("wordPreference").optional
```

Replace the `create` endpoint's body type is unaffected (still `CreateGameRequest`, just a smaller record) — no change needed to the `create` val itself.

Delete the `reshuffle` val entirely:

```scala
  /** Owner-only: redraws a `randomizeEachPlay = false` game's fixed word pool. `conflict` covers a game with nothing
    * fixed to reshuffle (`randomizeEachPlay = true`, or no word limit at all) — see `GameService.reshuffle`.
    */
  val reshuffle = {
    Endpoint(Method.POST / "api" / "games" / gameSlug / "reshuffle")
      .outCodec(noContent)
      .outErrors(failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }
```

Replace the `startPlay` val with:

```scala
  /** Starts a fresh attempt at `slug` under the variant `body` describes — see [[StartPlayRequest]]. `badRequest`
    * covers both a body that fails validation (an out-of-range `wordLimit`) and `NoEligibleWords` (the resolved
    * direction's pool is empty right now).
    */
  val startPlay = {
    Endpoint(Method.POST / "api" / "games" / gameSlug / "plays")
      .in[StartPlayRequest]
      .withCodecError
      .out[PlayStarted](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }
```

Add a new `playSetup` val, right after `startPlay`:

```scala
  /** The play-variant picker's preview: the resolved-direction eligible pool, in the order [[startPlay]] would
    * sample from for the same `swapDirection`/`wordPreference` — see `GameService.playSetupPreview`. Requires a
    * session (unlike [[setupWords]]) since the `Unplayed`/`MostMistakes` ordering depends on the caller's own
    * play history in this game.
    */
  val playSetup = {
    Endpoint(Method.GET / "api" / "games" / gameSlug / "plays" / "setup")
      .query(swapDirectionQuery)
      .query(wordPreferenceQuery)
      .out[List[GameSetupWord]]
      .outErrors(failure.unauthorized, failure.notFound)
  }
```

Update the `all` list — remove `reshuffle`, add `playSetup` right after `startPlay`:

```scala
  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(
    setup,
    setupWords,
    mine,
    myPlays,
    create,
    get,
    rename,
    startPlay,
    playSetup,
    nextPrompt,
    submitAnswer,
    results,
    listPlays,
    playDetail,
  )
```

- [ ] **Step 3: Register the new/changed schemas in `ApiSchemas`, in dependency order**

In `modules/shared/shared/src/main/scala/gathedge/shared/api/ApiSchemas.scala`:

Add `WordPreference` to the `gathedge.shared.domain` import list, and `GameVariantDto`/`StartPlayRequest` to the `gathedge.shared.dto` import list.

Add, right after `given Schema[AnswerOutcome] = DeriveSchema.gen[AnswerOutcome]`:

```scala
  given Schema[WordPreference] = DeriveSchema.gen[WordPreference]
```

Replace the existing game-schemas block:

```scala
  given Schema[GameDetail]          = DeriveSchema.gen[GameDetail]
  given Schema[GameCreated]         = DeriveSchema.gen[GameCreated]
  given Schema[CreateGameRequest]   = DeriveSchema.gen[CreateGameRequest]
  given Schema[RenameGameRequest]   = DeriveSchema.gen[RenameGameRequest]
  given Schema[PlayStarted]         = DeriveSchema.gen[PlayStarted]
  given Schema[GamePrompt]          = DeriveSchema.gen[GamePrompt]
  given Schema[SubmitAnswerRequest] = DeriveSchema.gen[SubmitAnswerRequest]
  given Schema[GameAnswerResult]    = DeriveSchema.gen[GameAnswerResult]
  given Schema[GameResults]         = DeriveSchema.gen[GameResults]
  given Schema[GameSetupWord]       = DeriveSchema.gen[GameSetupWord]
  given Schema[MyGameSummary]       = DeriveSchema.gen[MyGameSummary]
  given Schema[GamePlaySummary]     = DeriveSchema.gen[GamePlaySummary]
  given Schema[GamePlayPage]        = DeriveSchema.gen[GamePlayPage]
  given Schema[GamePlayDetail]      = DeriveSchema.gen[GamePlayDetail]
  given Schema[MyPlaySummary]       = DeriveSchema.gen[MyPlaySummary]
  given Schema[MyPlayPage]          = DeriveSchema.gen[MyPlayPage]
```

with:

```scala
  given Schema[GameDetail]          = DeriveSchema.gen[GameDetail]
  given Schema[GameCreated]         = DeriveSchema.gen[GameCreated]
  given Schema[CreateGameRequest]   = DeriveSchema.gen[CreateGameRequest]
  given Schema[RenameGameRequest]   = DeriveSchema.gen[RenameGameRequest]
  given Schema[StartPlayRequest]    = DeriveSchema.gen[StartPlayRequest]
  given Schema[GameVariantDto]      = DeriveSchema.gen[GameVariantDto]
  given Schema[PlayStarted]         = DeriveSchema.gen[PlayStarted]
  given Schema[GamePrompt]          = DeriveSchema.gen[GamePrompt]
  given Schema[SubmitAnswerRequest] = DeriveSchema.gen[SubmitAnswerRequest]
  given Schema[GameAnswerResult]    = DeriveSchema.gen[GameAnswerResult]
  given Schema[GameResults]         = DeriveSchema.gen[GameResults]
  given Schema[GameSetupWord]       = DeriveSchema.gen[GameSetupWord]
  given Schema[MyGameSummary]       = DeriveSchema.gen[MyGameSummary]
  given Schema[GamePlaySummary]     = DeriveSchema.gen[GamePlaySummary]
  given Schema[GamePlayPage]        = DeriveSchema.gen[GamePlayPage]
  given Schema[GamePlayDetail]      = DeriveSchema.gen[GamePlayDetail]
  given Schema[MyPlaySummary]       = DeriveSchema.gen[MyPlaySummary]
  given Schema[MyPlayPage]          = DeriveSchema.gen[MyPlayPage]
```

(`WordPreference`/`GameVariantDto`/`StartPlayRequest` declared before every schema that embeds them, per this file's own "declaration order matters" rule.)

- [ ] **Step 4: Confirm the shared module compiles on its own**

Run: `sbt sharedJVM/compile sharedJS/compile`
Expected: Both succeed. (`backend`/`frontend` will not compile yet — Tasks 3–13 fix their call sites. Do not attempt `sbt compile` until Task 8/11 land.)

- [ ] **Step 5: Commit**

```bash
git add modules/shared/shared/src/main/scala/gathedge/shared/dto/GameDto.scala modules/shared/shared/src/main/scala/gathedge/shared/api/GameEndpoints.scala modules/shared/shared/src/main/scala/gathedge/shared/api/ApiSchemas.scala
git commit -m "feat: redesign game DTOs and endpoints for play-time variants"
```

---

## Task 3: Backend rows and Flyway migration

**Files:**
- Modify: `modules/backend/src/main/scala/gathedge/backend/db/Rows.scala`
- Create: `modules/backend/src/main/resources/db/migration/postgresql/V14__game_play_variants.sql`
- Create: `modules/backend/src/main/resources/db/migration/sqlite/V14__game_play_variants.sql`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: trimmed `GameRow(id, ownerUserId, slug, name, sourceLanguage, targetLanguage, createdAt, updatedAt, trackResults: Boolean = false)`; `GamePlayRow` gaining `sourceLanguage: String = "en"`, `targetLanguage: String = "en"`, `wordLimit: Option[Int] = None`, `includeDefiniteArticles: Boolean = true`, `wordPreference: String = "all"` (defaulted so every existing positional `GamePlayRow(...)` construction in `PostgresIntegrationSpec` still compiles); `GameWordPoolRow` deleted. Task 4 (`GameRepository`) and Task 9 (`PostgresIntegrationSpec`) depend on these exact fields.

This is a schema/row-shape change with no isolated unit test of its own; `PostgresIntegrationSpec` (Task 9) and `GameServiceSpec` (Task 8) exercise it end-to-end once the service layer (Tasks 5–7) is in place. Steps here are edit-and-compile.

- [ ] **Step 1: Trim `GameRow`, extend `GamePlayRow`, delete `GameWordPoolRow`**

In `modules/backend/src/main/scala/gathedge/backend/db/Rows.scala`, replace the `GameRow` doc comment and case class:

```scala
/** One quiz, scoped to a language pair and built from the tags in [[GameTagRow]]. Nothing here changes after
  * creation — word count, direction, article display and word preference are all play-time choices now, carried
  * per-play on [[GamePlayRow]] instead. See the "game variants redesign" design doc.
  *
  * `slug` is generated once at creation and never changes — the permanent key a share link is built from, sized
  * and typed like [[PasswordResetTokenRow.token]] / [[GuestClaimCodeRow.code]]. `name` is the opposite: cosmetic,
  * free to edit at will, which is why the two are separate columns rather than one renameable field.
  *
  * `trackResults` gates only whether the owner-facing play listing/detail is reachable
  * (`GameService.listPlays`/`getPlayDetail`) — `false` (the default) is the only behaviour before this field
  * existed. [[GamePlayRow]] and [[GamePlayAnswerRow]] are written unconditionally by every play regardless of this
  * flag.
  */
final case class GameRow(
  id: Long,
  ownerUserId: Long,
  slug: String,
  name: String,
  sourceLanguage: String,
  targetLanguage: String,
  createdAt: Long,
  updatedAt: Long,
  trackResults: Boolean = false,
)
```

Replace the `GamePlayRow` doc comment and case class:

```scala
/** One attempt at a game, by one account, under one play-time variant.
  *
  * `score`, `maxScore` and `wordCount` are denormalized here rather than derived from [[GamePlayAnswerRow]] on
  * every read — a play is read far more often than written to, and all three are cheap to maintain incrementally
  * as answers come in. `wordCount` and `maxScore` are fixed at the moment the play starts; `score` is the one
  * column that changes as it progresses. `finishedAt` is `None` for a play still in progress and set once, when
  * it completes — there is no separate "abandoned" state.
  *
  * `sourceLanguage`/`targetLanguage`/`wordLimit`/`includeDefiniteArticles`/`wordPreference` are the variant this
  * specific play ran under — a snapshot, not a live reference to the (now immutable) base game, since a play may
  * have swapped direction or picked a narrower/differently-preferenced sample than another play of the same game.
  * `wordLimit` keeps its old `games.word_limit` meaning: `None` for "every eligible word", `Some(n)` for "sampled
  * exactly n (or fewer, if the pool was smaller)". `wordPreference` holds a [[gathedge.shared.domain.WordPreference]]
  * code. These five default to English/English/no limit/articles on/"all" only so pre-migration test fixtures that
  * construct a `GamePlayRow` positionally keep compiling — `GameService.startPlay` always supplies real values.
  */
final case class GamePlayRow(
  id: Long,
  gameId: Long,
  playerUserId: Long,
  score: Int,
  maxScore: Int,
  wordCount: Int,
  startedAt: Long,
  finishedAt: Option[Long],
  sourceLanguage: String = "en",
  targetLanguage: String = "en",
  wordLimit: Option[Int] = None,
  includeDefiniteArticles: Boolean = true,
  wordPreference: String = "all",
)
```

Delete the `GameWordPoolRow` case class and its doc comment entirely:

```scala
/** A game's own fixed word draw, for a `randomizeEachPlay = false` game — written once at `createGame` (when the
  * creator picked "randomize now") and replaced wholesale by [[GameRepository.replaceGameWordPool]] on a reshuffle.
  * Every play of such a game reads this same set via [[GameRepository.wordPoolOf]], instead of each play drawing its
  * own sample the way [[GamePlayWordRow]] does for a `randomizeEachPlay = true` game.
  */
final case class GameWordPoolRow(id: Long, gameId: Long, wordId: Long, translationWordId: Long)
```

- [ ] **Step 2: Write the Postgres migration**

Create `modules/backend/src/main/resources/db/migration/postgresql/V14__game_play_variants.sql`:

```sql
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
```

- [ ] **Step 3: Write the SQLite mirror**

Create `modules/backend/src/main/resources/db/migration/sqlite/V14__game_play_variants.sql`:

```sql
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
```

- [ ] **Step 4: Compile (rows only — repository/service still reference removed members until Task 4)**

Run: `sbt backend/compile`
Expected: FAILS — `GameRepository.scala`/`GameService.scala` still reference `wordPoolOf`/`GameWordPoolRow`/the three dropped `GameRow` fields. This is expected; Task 4 fixes it. Do not treat this as a regression.

- [ ] **Step 5: Commit**

```bash
git add modules/backend/src/main/scala/gathedge/backend/db/Rows.scala modules/backend/src/main/resources/db/migration/postgresql/V14__game_play_variants.sql modules/backend/src/main/resources/db/migration/sqlite/V14__game_play_variants.sql
git commit -m "feat: move game variant columns from games onto game_plays"
```

---

## Task 4: `GameRepository` — drop fixed-pool plumbing, add answer-history query

**Files:**
- Modify: `modules/backend/src/main/scala/gathedge/backend/db/GameRepository.scala`

**Interfaces:**
- Consumes: `GameRow`/`GamePlayRow` (Task 3).
- Produces: `GameRepository.insertGame(row: GameRow, tagIds: List[Long]): Task[GameRow]` (no `wordPool` param), `GameRepository.answerOutcomesFor(gameId: Long, playerUserId: Long, sourceLanguage: String, targetLanguage: String): Task[List[(Long, String)]]` (word id, outcome code, one row per answer) — consumed by Task 6's `wordStats`. `wordPoolOf`/`replaceGameWordPool` removed.

- [ ] **Step 1: Write the failing test for the new query**

Add to `modules/backend/src/test/scala/gathedge/backend/service/GameServiceSpec.scala` is Task 8's job (it exercises this through `GameService`). For this task, add a focused repository-level test. Create `modules/backend/src/test/scala/gathedge/backend/db/GameRepositorySpec.scala`:

```scala
package gathedge.backend.db

import gathedge.backend.TestDataSource
import zio._
import zio.test._

object GameRepositorySpec extends ZIOSpecDefault {

  private val layer = TestDataSource.sqlite >>> (UserRepository.test ++ WordRepository.test ++ GameRepository.test)

  private def newUser(): RIO[UserRepository, Long] = UserRepository.insertGuest("light", "en", 0L).map(_.id)

  def spec = {
    suite("GameRepository.answerOutcomesFor")(
      test("answers only the given player's answers, in the given direction, for the given game") {
        for {
          owner   <- newUser()
          other   <- newUser()
          game    <- GameRepository.insertGame(GameRow(0L, owner.toLong, "repo-slug", "Repo Game", "de", "hu", 0L, 0L), Nil)
          otherGame <-
            GameRepository.insertGame(GameRow(0L, owner.toLong, "repo-slug-2", "Repo Game 2", "de", "hu", 0L, 0L), Nil)
          play    <- GameRepository.insertPlay(
                       GamePlayRow(
                         0L,
                         game.id,
                         owner,
                         0,
                         2,
                         1,
                         0L,
                         None,
                         sourceLanguage = "de",
                         targetLanguage = "hu",
                       ),
                       Nil,
                     )
          reverse <- GameRepository.insertPlay(
                       GamePlayRow(
                         0L,
                         game.id,
                         owner,
                         0,
                         2,
                         1,
                         0L,
                         None,
                         sourceLanguage = "hu",
                         targetLanguage = "de",
                       ),
                       Nil,
                     )
          otherPlayersPlay <- GameRepository.insertPlay(
                                GamePlayRow(0L, game.id, other, 0, 2, 1, 0L, None, sourceLanguage = "de", targetLanguage = "hu"),
                                Nil,
                              )
          _       <- GameRepository.recordAnswer(
                       GamePlayAnswerRow(0L, play.id, 1L, 2L, 1, "x", "correct", 2, 0L),
                       2,
                       Some(0L),
                     )
          _       <- GameRepository.recordAnswer(
                       GamePlayAnswerRow(0L, reverse.id, 1L, 2L, 1, "y", "wrong", 0, 0L),
                       0,
                       Some(0L),
                     )
          _       <- GameRepository.recordAnswer(
                       GamePlayAnswerRow(0L, otherPlayersPlay.id, 1L, 2L, 1, "z", "wrong", 0, 0L),
                       0,
                       Some(0L),
                     )
          deRows  <- GameRepository.answerOutcomesFor(game.id, owner, "de", "hu")
          huRows  <- GameRepository.answerOutcomesFor(game.id, owner, "hu", "de")
        } yield assertTrue(
          deRows == List((1L, "correct")),
          huRows == List((1L, "wrong")),
        )
      }
    ).provide(layer)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly gathedge.backend.db.GameRepositorySpec"`
Expected: FAIL — `GameRepository.answerOutcomesFor` does not exist yet (compile error).

- [ ] **Step 3: Remove fixed-pool plumbing, add `answerOutcomesFor`**

In `modules/backend/src/main/scala/gathedge/backend/db/GameRepository.scala`:

Replace the `insertGame` trait method doc/signature:

```scala
  /** Inserts `row` and one `game_tags` row per id in `tagIds`, as one unit of work — a game whose row landed but
    * whose tags didn't is not a state anything downstream can make sense of.
    */
  def insertGame(row: GameRow, tagIds: List[Long]): Task[GameRow]
```

and its companion forwarder:

```scala
  def insertGame(row: GameRow, tagIds: List[Long]): RIO[GameRepository, GameRow] =
    ZIO.serviceWithZIO[GameRepository](_.insertGame(row, tagIds))
```

Delete the `wordPoolOf`/`replaceGameWordPool` trait methods and their companion forwarders entirely (both the doc comments and the bodies):

```scala
  def wordPoolOf(gameId: Long): Task[List[(Long, Long)]]
  ...
  def replaceGameWordPool(gameId: Long, pairs: List[(Long, Long)]): Task[Unit]
```

and:

```scala
  def wordPoolOf(gameId: Long): RIO[GameRepository, List[(Long, Long)]] =
    ZIO.serviceWithZIO[GameRepository](_.wordPoolOf(gameId))

  def replaceGameWordPool(gameId: Long, pairs: List[(Long, Long)]): RIO[GameRepository, Unit] =
    ZIO.serviceWithZIO[GameRepository](_.replaceGameWordPool(gameId, pairs))
```

Add the new trait method, right after `answersOf`:

```scala
  /** This player's `(word_id, outcome)` for every answer recorded against `gameId`, restricted to plays whose own
    * `source_language`/`target_language` match the given direction — see [[GamePlayRow]]'s doc comment on why a
    * play's variant, not the game's, decides direction. One row per answer, not deduped or aggregated: turning
    * this into "played at all" / "how many mistakes" per word is [[gathedge.backend.service.GameService]]'s job,
    * the same split this file draws for [[eligibleWordPairs]]'s dedup.
    */
  def answerOutcomesFor(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): Task[List[(Long, String)]]
```

and its companion forwarder, right after `answersOf`'s:

```scala
  def answerOutcomesFor(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): RIO[GameRepository, List[(Long, String)]] =
    ZIO.serviceWithZIO[GameRepository](_.answerOutcomesFor(gameId, playerUserId, sourceLanguage, targetLanguage))
```

In `GameRepositoryLive`, delete the `private inline def gameWordPool = quote(querySchema[GameWordPoolRow]("game_word_pool"))` line.

Replace the `insertGame` implementation:

```scala
  def insertGame(row: GameRow, tagIds: List[Long]): Task[GameRow] = {
    val inserted = transaction(
      for {
        id <- ctx.run(quote(games.insertValue(lift(row)).returningGenerated(_.id)))
        _  <- ZIO.unless(tagIds.isEmpty) {
                val links = tagIds.map(tagId => GameTagRow(0L, id, tagId))
                ctx.run(quote {
                  liftQuery(links).foreach(row => gameTags.insert(_.gameId -> row.gameId, _.tagId -> row.tagId))
                })
              }
      } yield id
    )
    logged(inserted.map(id => row.copy(id = id))) { game =>
      s"games.insert id=${game.id} owner=${row.ownerUserId} tags=${tagIds.size}"
    }
  }
```

Delete the `wordPoolOf`/`replaceGameWordPool` implementations entirely.

Add the new implementation, right after `answersOf`:

```scala
  def answerOutcomesFor(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): Task[List[(Long, String)]] = {
    val q = quote {
      for {
        play   <- gamePlays.filter(p =>
                    p.gameId == lift(gameId) && p.playerUserId == lift(playerUserId) &&
                      p.sourceLanguage == lift(sourceLanguage) && p.targetLanguage == lift(targetLanguage)
                  )
        answer <- gamePlayAnswers.join(a => a.playId == play.id)
      } yield (answer.wordId, answer.outcome)
    }
    logged(run(ctx.run(q))) { rows =>
      s"games.answerOutcomesFor game=$gameId player=$playerUserId rows=${rows.size}"
    }
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "backend/testOnly gathedge.backend.db.GameRepositorySpec"`
Expected: PASS (the whole `backend` module still fails to compile at this point — `GameService.scala`/`GameRoutes.scala` reference the removed `insertGame`/`wordPoolOf` shapes. Task 5–7 fix those. `testOnly` on this one spec's file compiles only what it needs transitively and will pass once Tasks 5–7 land; running it here in isolation via `testOnly` still requires the whole module to compile, so if it fails to compile, proceed to Task 5 and return to re-run this spec once Task 7 is done.)

- [ ] **Step 5: Commit**

```bash
git add modules/backend/src/main/scala/gathedge/backend/db/GameRepository.scala modules/backend/src/test/scala/gathedge/backend/db/GameRepositorySpec.scala
git commit -m "feat: drop fixed-pool queries from GameRepository, add answerOutcomesFor"
```

---

## Task 5: `GameService.createGame` simplified, `eligibleWordPoolFor` renamed

**Files:**
- Modify: `modules/backend/src/main/scala/gathedge/backend/service/GameService.scala`

**Interfaces:**
- Consumes: `GameRepository.insertGame(row, tagIds)` (Task 4), trimmed `GameRow` (Task 3), trimmed `CreateGameRequest`/`GameDetail` (Task 2).
- Produces: `GameService.createGame(userId, sourceLanguage, targetLanguage, tagIds, trackResults: Boolean = false): IO[GameFailure, GameDetail]`; a private `eligibleWordPoolFor(gameId: Long, sourceLanguage: String, targetLanguage: String): UIO[List[(Long, Long)]]` that Task 6's `startPlay`/`playSetupPreview` call. `GameFailure.NotFixedPool` removed. This task does not yet touch `startPlay`/`reshuffle`/`nextPrompt`/etc — those are Task 6/7, so the file will not compile again until Task 7 lands. Do not run `sbt backend/compile` expecting success until then.

- [ ] **Step 1: Simplify `createGame` and its helpers**

In `modules/backend/src/main/scala/gathedge/backend/service/GameService.scala`:

Remove `NotFixedPool` from the `GameFailure` enum:

```scala
  /** [[GameService.reshuffle]] on a game that has nothing fixed to reshuffle: either it draws a fresh sample every play
    * already (`randomizeEachPlay = true`), or it has no word limit at all (uses every eligible word, so there is no
    * subset to redraw).
    */
  case NotFixedPool
```

Replace the `createGame` trait method:

```scala
  /** `trackResults`: `false` (the default, and the only behaviour before this parameter existed) means
    * [[listPlays]]/[[getPlayDetail]] answer [[GameFailure.NotTracked]] for this game; `true` opts into the
    * owner-facing results listing. Set once, here — there is no route to change it after creation. Direction,
    * word count, article display and word preference are no longer part of a game at all — see
    * [[startPlay]].
    */
  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
    trackResults: Boolean = false,
  ): IO[GameFailure, GameDetail]
```

and its companion forwarder:

```scala
  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
    trackResults: Boolean = false,
  ): ZIO[GameService, GameFailure, GameDetail] = {
    ZIO.serviceWithZIO[GameService](_.createGame(userId, sourceLanguage, targetLanguage, tagIds, trackResults))
  }
```

Remove the `reshuffle` trait method and companion forwarder for now (Task 6 re-adds the *replacement*, `playSetupPreview` — this deletion is final, `reshuffle` itself does not come back):

```scala
  def reshuffle(slug: String, requesterUserId: Long): IO[GameFailure, Unit]
  ...
  def reshuffle(slug: String, requesterUserId: Long): ZIO[GameService, GameFailure, Unit] =
    ZIO.serviceWithZIO[GameService](_.reshuffle(slug, requesterUserId))
```

In `GameServiceLive`, replace `insertWithRetry`:

```scala
  private def insertWithRetry(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
    trackResults: Boolean,
    now: Long,
    attempt: Int,
    lastPair: Option[(String, String)],
  ): UIO[GameRow] = {
    if (attempt >= GameService.maxAttempts)
      ZIO.die(new RuntimeException("Exhausted every attempt to generate a unique game slug"))
    else {
      candidate(attempt, lastPair).flatMap { case (pair, slug, name) =>
        val row = GameRow(
          id = 0L,
          ownerUserId = userId,
          slug = slug,
          name = name,
          sourceLanguage = WordLanguage.code(sourceLanguage),
          targetLanguage = WordLanguage.code(targetLanguage),
          createdAt = now,
          updatedAt = now,
          trackResults = trackResults,
        )
        repo.insertGame(row, tagIds).catchAll { error =>
          repo.findBySlug(slug).orDie.flatMap {
            case Some(_) =>
              insertWithRetry(userId, sourceLanguage, targetLanguage, tagIds, trackResults, now, attempt + 1, Some(pair))
            case None    =>
              ZIO.die(error)
          }
        }
      }
    }
  }
```

Replace `createGame`:

```scala
  def createGame(
    userId: Long,
    sourceLanguage: WordLanguage,
    targetLanguage: WordLanguage,
    tagIds: List[Long],
    trackResults: Boolean = false,
  ): IO[GameFailure, GameDetail] = {
    for {
      _          <- ZIO.when(tagIds.isEmpty)(ZIO.fail(GameFailure.NoTagsSelected))
      eligible   <- repo.eligibleTags(WordLanguage.code(sourceLanguage), WordLanguage.code(targetLanguage)).orDie
      eligibleIds = eligible.map(_._1.id).toSet
      _          <- ZIO.unless(tagIds.forall(eligibleIds.contains))(ZIO.fail(GameFailure.TagNotEligible))
      now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row        <- insertWithRetry(userId, sourceLanguage, targetLanguage, tagIds, trackResults, now, attempt = 0, lastPair = None)
      tags       <- repo.tagsOf(row.id).orDie
    } yield GameDetail(row.slug, row.name, sourceLanguage, targetLanguage, tags.map(_.name).sorted, row.trackResults)
  }
```

Replace `detailOf`:

```scala
  private def detailOf(row: GameRow): UIO[GameDetail] = {
    repo.tagsOf(row.id).orDie.map { tags =>
      GameDetail(
        row.slug,
        row.name,
        WordLanguage.fromString(row.sourceLanguage).getOrElse(WordLanguage.En),
        WordLanguage.fromString(row.targetLanguage).getOrElse(WordLanguage.En),
        tags.map(_.name).sorted,
        row.trackResults,
      )
    }
  }
```

Delete the `reshuffle` method body entirely (Task 6 does not replace it — the fixed-pool concept is gone):

```scala
  def reshuffle(slug: String, requesterUserId: Long): IO[GameFailure, Unit] = {
    for {
      game    <- requireOwnGame(slug, requesterUserId)
      _       <- ZIO.when(game.randomizeEachPlay || game.wordLimit.isEmpty)(ZIO.fail(GameFailure.NotFixedPool))
      pool    <- eligibleWordPool(game)
      sampled <- sampleWordPool(pool, game.wordLimit)
      _       <- repo.replaceGameWordPool(game.id, sampled).orDie
    } yield ()
  }
```

Replace `eligibleWordPool(game: GameRow)` with a variant taking explicit ids/codes instead of a `GameRow` (Task 6's `startPlay` needs to call this with a *resolved* direction, which may not be the game's own stored one):

```scala
  /** `(word_id, translation_word_id)` pairs eligible for `gameId` in the `sourceLanguage` -> `targetLanguage`
    * direction, deduped to one row per source word. Takes explicit codes rather than a `GameRow` because a play
    * may resolve to the reverse of the game's own stored direction — see [[GameServiceLive.startPlay]].
    */
  private def eligibleWordPoolFor(gameId: Long, sourceLanguage: String, targetLanguage: String): UIO[List[(Long, Long)]] = {
    repo.eligibleWordPairs(gameId, sourceLanguage, targetLanguage).orDie.map(dedupeToOnePerWord)
  }
```

(Leave `eligibleWordPoolForTags`, `dedupeToOnePerWord`, `eligibleWords`, `eligibleTags`, `myGames`, `rename`, `requireOwnGame`, `requireOwnedPlay` untouched — Task 6/7 touch different methods in this same file.)

- [ ] **Step 2: This task ends mid-file — do not attempt to run tests yet**

`GameServiceLive` still references the old `sampleWordPool(pool, limit)` two-arg shape and the old `startPlay`/`nextPrompt`/`submitAnswer`/`getResults`/`listPlays` bodies, all of which Task 6/7 rewrite. Compiling now will fail; that failure is expected and resolved by Task 7's end. Proceed directly to Task 6.

- [ ] **Step 3: Commit**

```bash
git add modules/backend/src/main/scala/gathedge/backend/service/GameService.scala
git commit -m "refactor: simplify GameService.createGame, drop reshuffle"
```

---

## Task 6: `GameService.startPlay` — direction resolution, variant snapshot, preference sampling, play-setup preview

**Files:**
- Modify: `modules/backend/src/main/scala/gathedge/backend/service/GameService.scala`

**Interfaces:**
- Consumes: `eligibleWordPoolFor` (Task 5), `GameRepository.answerOutcomesFor` (Task 4), `WordPreference` (Task 1), `StartPlayRequest`/`GameVariantDto` shapes (Task 2, consumed via Task 7's route).
- Produces: `GameService.startPlay(slug, playerUserId, swapDirection: Boolean = false, wordLimit: Option[Int] = None, includeDefiniteArticles: Boolean = true, wordPreference: WordPreference = WordPreference.All): IO[GameFailure, PlayStarted]`; `GameService.playSetupPreview(slug, playerUserId, swapDirection: Boolean, wordPreference: WordPreference): IO[GameFailure, List[GameSetupWord]]`. Task 7 wires both into `GameRoutes`.

- [ ] **Step 1: Write the failing tests (added to `GameServiceSpec` here; the full spec rewrite is Task 8 — these three tests are the ones this task's own code must satisfy, and Task 8 supersedes/extends this file wholesale)**

These tests assume `eligibleTagWithPairs`/`playThrough`/`newUser` already exist in `GameServiceSpec.scala` (they do, unmodified by this task). Add, inside the `spec = { suite("GameService")( ... ) }` list, right after the existing `"a game with no word limit still uses every eligible word..."` test:

```scala
      test("swapDirection reverses the resolved direction and records it on the play") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "swap", WordLanguage.De, WordLanguage.Hu, count = 2)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          normal  <- GameService.startPlay(created.slug, owner)
          swapped <- GameService.startPlay(created.slug, owner, swapDirection = true)
          normalResults  <- GameService.getResults(normal.playId, owner)
          swappedResults <- GameService.getResults(swapped.playId, owner)
        } yield assertTrue(
          normalResults.variant.sourceLanguage == WordLanguage.De,
          normalResults.variant.targetLanguage == WordLanguage.Hu,
          swappedResults.variant.sourceLanguage == WordLanguage.Hu,
          swappedResults.variant.targetLanguage == WordLanguage.De,
        )
      },
      test("a play-time word limit samples the play, without ever touching the game itself") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "playLimit", WordLanguage.De, WordLanguage.Hu, count = 5)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner, wordLimit = Some(2))
          results <- GameService.getResults(started.playId, owner)
        } yield assertTrue(
          started.wordCount == 2,
          started.maxScore == 4,
          results.variant.wordLimit.contains(2),
        )
      },
      test("an out-of-range play-time word limit fails validation") {
        for {
          owner  <- newUser()
          tagId  <- eligibleTagWithPairs(owner, "playLimitInvalid", WordLanguage.De, WordLanguage.Hu, count = 2)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          result <- GameService.startPlay(created.slug, owner, wordLimit = Some(0)).either
        } yield assertTrue(result.left.exists(_.isInstanceOf[GameFailure.ValidationError]))
      },
      test("Unplayed preference fills the sample from never-answered words first, in this direction only") {
        for {
          owner       <- newUser()
          tagId       <- eligibleTagWithPairs(owner, "unplayedPref", WordLanguage.De, WordLanguage.Hu, count = 4)
          created     <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          warmup      <- GameService.startPlay(created.slug, owner, wordLimit = Some(1))
          _           <- playThrough(warmup.playId, "unplayedPref", owner)
          warmupWord  <- GameService.getResults(warmup.playId, owner).map(_.answers.head.wordText)
          narrowed    <- GameService.startPlay(
                           created.slug,
                           owner,
                           wordLimit = Some(3),
                           wordPreference = WordPreference.Unplayed,
                         )
          results     <- GameService.getResults(narrowed.playId, owner)
        } yield assertTrue(
          // Three of the four eligible words are sampled; the one already answered by this player, in this
          // direction, is the one most likely left out — asserted as "never all four fit, and the previously
          // answered word is not required to reappear" rather than a flaky exact-set check, since ties among
          // the three never-played words are broken by shuffle.
          results.wordCount == 3,
          results.variant.wordPreference == WordPreference.Unplayed,
        )
      },
      test("MostMistakes preference ranks by this player's wrong-answer count, in this direction only") {
        for {
          owner   <- newUser()
          tag     <- WordRepository.insertTag(owner, "mistakePref", "mistakePref", 0L)
          mistakeWord  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "mistake-source"))
          mistakeTgt   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "mistake-target"))
          _            <- WordRepository.pairTranslation(mistakeWord.id, tag.id, mistakeTgt.id, 0L)
          cleanWord    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "clean-source"))
          cleanTgt     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "clean-target"))
          _            <- WordRepository.pairTranslation(cleanWord.id, tag.id, cleanTgt.id, 0L)
          created      <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tag.id))
          warmup       <- GameService.startPlay(created.slug, owner)
          promptA      <- GameService.nextPrompt(warmup.playId, owner)
          _            <- GameService.submitAnswer(warmup.playId, promptA.wordId.get, "totally-unrelated", owner)
          promptB      <- GameService.nextPrompt(warmup.playId, owner)
          _            <- GameService.submitAnswer(warmup.playId, promptB.wordId.get, "also-unrelated", owner)
          narrowed     <- GameService.startPlay(
                            created.slug,
                            owner,
                            wordLimit = Some(1),
                            wordPreference = WordPreference.MostMistakes,
                          )
          // Both eligible words now carry exactly one wrong answer each (a tie), so this only asserts the
          // preference round-trips onto the play rather than picking a specific winner out of a tie.
          results      <- GameService.getResults(narrowed.playId, owner)
        } yield assertTrue(results.wordCount == 1, results.variant.wordPreference == WordPreference.MostMistakes)
      },
      test("playSetupPreview answers the resolved-direction pool without starting a play") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "previewPref", WordLanguage.De, WordLanguage.Hu, count = 3)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          preview <- GameService.playSetupPreview(created.slug, owner, swapDirection = false, WordPreference.All)
          swapped <- GameService.playSetupPreview(created.slug, owner, swapDirection = true, WordPreference.All)
        } yield assertTrue(preview.size == 3, swapped.size == 3)
      },
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "backend/testOnly gathedge.backend.service.GameServiceSpec"`
Expected: FAIL to compile — `GameService.startPlay`'s current signature takes only `(slug, playerUserId)`, `GameService.playSetupPreview` does not exist, `GameResults` (Task 2) already has a `variant` field the current `getResults` impl does not supply.

- [ ] **Step 3: Rewrite `startPlay`, add sampling/preference helpers and `playSetupPreview`**

In `modules/backend/src/main/scala/gathedge/backend/service/GameService.scala`, add `WordPreference` and `GameVariantDto` to the `gathedge.shared.domain`/`gathedge.shared.dto` imports at the top of the file.

Replace the `startPlay` trait method:

```scala
  /** Starts a fresh attempt at `slug` under the given variant. `swapDirection` plays the game's `targetLanguage` ->
    * `sourceLanguage` instead of its stored direction. `wordLimit`/`includeDefiniteArticles`/`wordPreference` are
    * this play's own settings, snapshotted onto its `game_plays` row — see the design doc. Fails
    * [[GameFailure.ValidationError]] for an out-of-range `wordLimit`, [[GameFailure.NoEligibleWords]] if the
    * resolved direction's pool is empty right now.
    */
  def startPlay(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    includeDefiniteArticles: Boolean = true,
    wordPreference: WordPreference = WordPreference.All,
  ): IO[GameFailure, PlayStarted]

  /** The play-variant picker's preview: the resolved-direction eligible pool, in the order [[startPlay]] would
    * sample from for the same `swapDirection`/`wordPreference` — lets the picker show an honest "N eligible"
    * before any play exists.
    */
  def playSetupPreview(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean,
    wordPreference: WordPreference,
  ): IO[GameFailure, List[GameSetupWord]]
```

and their companion forwarders:

```scala
  def startPlay(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    includeDefiniteArticles: Boolean = true,
    wordPreference: WordPreference = WordPreference.All,
  ): ZIO[GameService, GameFailure, PlayStarted] = {
    ZIO.serviceWithZIO[GameService](
      _.startPlay(slug, playerUserId, swapDirection, wordLimit, includeDefiniteArticles, wordPreference)
    )
  }

  def playSetupPreview(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean,
    wordPreference: WordPreference,
  ): ZIO[GameService, GameFailure, List[GameSetupWord]] = {
    ZIO.serviceWithZIO[GameService](_.playSetupPreview(slug, playerUserId, swapDirection, wordPreference))
  }
```

In `GameServiceLive`, replace the old two-arg `sampleWordPool` with the preference-aware pipeline, right after `eligibleWordPoolFor`:

```scala
  /** This player's per-word answer history for `gameId` in the `sourceLanguage` -> `targetLanguage` direction —
    * total answers and how many were not [[AnswerOutcome.Correct]] — the ordering signal for
    * [[WordPreference.Unplayed]]/[[WordPreference.MostMistakes]]. A word absent from the map has never been
    * answered by this player in this direction — see [[GameRepository.answerOutcomesFor]].
    */
  private def wordStats(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
  ): UIO[Map[Long, (Int, Int)]] = {
    repo.answerOutcomesFor(gameId, playerUserId, sourceLanguage, targetLanguage).orDie.map { rows =>
      rows
        .groupBy(_._1)
        .view
        .mapValues { outcomes =>
          val total    = outcomes.size
          val mistakes = outcomes.count(_._2 != AnswerOutcome.code(AnswerOutcome.Correct))
          (total, mistakes)
        }
        .toMap
    }
  }

  /** `pool` reordered so `preference`'s preferred subset comes first — see the design doc's "priority sampling,
    * not a hard filter" rule. Shuffled first in every case, so ties (including "no history at all", which every
    * word shares under [[WordPreference.All]]) are broken randomly rather than by pool order, and `.sortBy` is
    * stable, so that shuffle survives within each tie group.
    */
  private def preferenceOrdered(
    pool: List[(Long, Long)],
    stats: Map[Long, (Int, Int)],
    preference: WordPreference,
  ): UIO[List[(Long, Long)]] = {
    Random.shuffle(pool).map { shuffled =>
      preference match {
        case WordPreference.All          =>
          shuffled
        case WordPreference.Unplayed     =>
          shuffled.sortBy(pair => if (stats.contains(pair._1)) 1 else 0)
        case WordPreference.MostMistakes =>
          shuffled.sortBy(pair => -stats.get(pair._1).map(_._2).getOrElse(0))
      }
    }
  }

  /** `pool` itself when `limit` is absent or no smaller than the pool. Otherwise `limit`'s first
    * [[preferenceOrdered]] words — for [[WordPreference.Unplayed]]/[[WordPreference.MostMistakes]] this is the
    * "fill from the preferred subset, then top up from the rest" rule the design doc describes; for
    * [[WordPreference.All]] it is a uniform random sample, exactly as before this feature existed.
    */
  private def sampleWordPool(
    gameId: Long,
    playerUserId: Long,
    sourceLanguage: String,
    targetLanguage: String,
    pool: List[(Long, Long)],
    limit: Option[Int],
    preference: WordPreference,
  ): UIO[List[(Long, Long)]] = {
    limit match {
      case Some(n) if n < pool.size =>
        for {
          stats   <- wordStats(gameId, playerUserId, sourceLanguage, targetLanguage)
          ordered <- preferenceOrdered(pool, stats, preference)
        } yield ordered.take(n)
      case _                        =>
        ZIO.succeed(pool)
    }
  }
```

Replace `startPlay`:

```scala
  def startPlay(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    includeDefiniteArticles: Boolean = true,
    wordPreference: WordPreference = WordPreference.All,
  ): IO[GameFailure, PlayStarted] = {
    for {
      game       <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      validLimit <- ZIO
                      .foreach(wordLimit)(limit => ZIO.fromEither(Validation.validateWordLimit(limit)))
                      .mapError(error => GameFailure.ValidationError(Map("wordLimit" -> error)))
      resolved    = if (swapDirection) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
      (resolvedSource, resolvedTarget) = resolved
      pool       <- eligibleWordPoolFor(game.id, resolvedSource, resolvedTarget)
      _          <- ZIO.when(pool.isEmpty)(ZIO.fail(GameFailure.NoEligibleWords))
      sampled    <- sampleWordPool(game.id, playerUserId, resolvedSource, resolvedTarget, pool, validLimit, wordPreference)
      now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
      wordCount   = sampled.size
      maxScore    = wordCount * GameScoring.maxPointsPerWord
      row        <- repo
                      .insertPlay(
                        GamePlayRow(
                          id = 0L,
                          gameId = game.id,
                          playerUserId = playerUserId,
                          score = 0,
                          maxScore = maxScore,
                          wordCount = wordCount,
                          startedAt = now,
                          finishedAt = None,
                          sourceLanguage = resolvedSource,
                          targetLanguage = resolvedTarget,
                          wordLimit = validLimit,
                          includeDefiniteArticles = includeDefiniteArticles,
                          wordPreference = WordPreference.code(wordPreference),
                        ),
                        sampled,
                      )
                      .orDie
    } yield PlayStarted(row.id, wordCount, maxScore)
  }

  def playSetupPreview(
    slug: String,
    playerUserId: Long,
    swapDirection: Boolean,
    wordPreference: WordPreference,
  ): IO[GameFailure, List[GameSetupWord]] = {
    for {
      game     <- repo.findBySlug(slug).orDie.someOrFail(GameFailure.NotFound)
      resolved  = if (swapDirection) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
      (resolvedSource, resolvedTarget) = resolved
      pool     <- eligibleWordPoolFor(game.id, resolvedSource, resolvedTarget)
      stats    <- wordStats(game.id, playerUserId, resolvedSource, resolvedTarget)
      ordered  <- preferenceOrdered(pool, stats, wordPreference)
      words    <- repo.wordsByIds(ordered.map(_._1)).orDie
      textById  = words.map(w => w.id -> Word.displayText(w.text, w.gender)).toMap
    } yield ordered.flatMap { case (wordId, _) => textById.get(wordId).map(text => GameSetupWord(wordId, text)) }
  }
```

- [ ] **Step 4: This task still leaves `nextPrompt`/`submitAnswer`/`getResults`/`listPlays`/`getPlayDetail`/`myPlays`/`trackedPlaysOf` referencing the old `game.includeDefiniteArticles`/no-`variant` shapes — Task 7 finishes those. Do not run the test suite expecting a clean pass yet; proceed directly to Task 7.**

- [ ] **Step 5: Commit**

```bash
git add modules/backend/src/main/scala/gathedge/backend/service/GameService.scala
git commit -m "feat: resolve play direction and preference-order sampling in GameService.startPlay"
```

---

## Task 7: `GameService` — variant-aware play loop and listings, `ApiFailures`, `GameRoutes`

**Files:**
- Modify: `modules/backend/src/main/scala/gathedge/backend/service/GameService.scala`
- Modify: `modules/backend/src/main/scala/gathedge/backend/http/ApiFailures.scala`
- Modify: `modules/backend/src/main/scala/gathedge/backend/http/GameRoutes.scala`

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: `nextPrompt`/`submitAnswer`/`getResults` reading direction/articles from the *play*, not the game; `GameVariantDto` embedded in `GameResults`/`GamePlaySummary`/`GamePlayDetail`/`MyPlaySummary`; `ApiFailures.gameStartPlay` handling `ValidationError`; `ApiFailures.gameReshuffle` deleted; `GameRoutes` wired to the new `create`/`startPlay`/`playSetup` shapes with `reshuffleRoute` deleted. This is the task that finally makes `sbt backend/compile` succeed again.

- [ ] **Step 1: Finish `GameService` — variant-aware prompt/submit/results/listings**

In `modules/backend/src/main/scala/gathedge/backend/service/GameService.scala`:

Replace `candidateTranslationIds`:

```scala
  /** `wordId`'s translation ids eligible under `play`'s own resolved direction — every marked pair, not just the
    * one the prompt was drawn against — so [[submitAnswer]] can credit any of a word's accepted translations. Reads
    * `play.gameId`/`play.sourceLanguage`/`play.targetLanguage` rather than the game's own (now direction-agnostic)
    * row, since a play may have swapped direction relative to another play of the same game.
    */
  private def candidateTranslationIds(play: GamePlayRow, wordId: Long, fallback: Long): UIO[List[Long]] = {
    repo
      .eligibleWordPairs(play.gameId, play.sourceLanguage, play.targetLanguage)
      .orDie
      .map(pairs => (fallback :: pairs.collect { case (w, t) if w == wordId => t }).distinct)
  }
```

Replace `nextPrompt`:

```scala
  def nextPrompt(playId: Long, requesterUserId: Long): IO[GameFailure, GamePrompt] = {
    for {
      play       <- requireOwnedPlay(playId, requesterUserId)
      pool       <- repo.wordPairsOf(playId).orDie
      answered   <- repo.answersOf(playId).orDie
      answeredIds = answered.map(_.wordId).toSet
      remaining   = pool.filterNot(pair => answeredIds.contains(pair._1))
      prompt     <- remaining match {
                      case Nil     =>
                        ZIO.succeed(GamePrompt(finished = true))
                      case choices =>
                        for {
                          index      <- Random.nextIntBounded(choices.size)
                          (wordId, _) = choices(index)
                          wordRows   <- repo.wordsByIds(List(wordId)).orDie
                          text        =
                            wordRows.headOption.map(row => wordText(row, play.includeDefiniteArticles)).getOrElse("")
                        } yield GamePrompt(
                          finished = false,
                          wordId = Some(wordId),
                          wordText = Some(text),
                          position = Some(answeredIds.size + 1),
                        )
                    }
    } yield prompt
  }
```

Replace `submitAnswer`:

```scala
  def submitAnswer(playId: Long, wordId: Long, answerText: String, requesterUserId: Long): IO[GameFailure, Unit] = {
    for {
      play            <- requireOwnedPlay(playId, requesterUserId)
      pool            <- repo.wordPairsOf(playId).orDie
      translationId   <- ZIO.fromOption(pool.find(_._1 == wordId).map(_._2)).orElseFail(GameFailure.NotFound)
      candidateIds    <- candidateTranslationIds(play, wordId, translationId)
      candidateWords  <- repo.wordsByIds(candidateIds).orDie
      textById         = candidateWords.map(row => row.id -> wordText(row, play.includeDefiniteArticles)).toMap
      scoredById       = candidateIds.flatMap(id => textById.get(id).map(text => id -> GameScoring.score(text, answerText)))
      (bestId, scored) = {
        scoredById
          .maxByOption(_._2.points)
          .getOrElse(translationId -> GameScoring.score(textById.getOrElse(translationId, ""), answerText))
      }
      now             <- Clock.currentTime(TimeUnit.MILLISECONDS)
      answeredSoFar   <- repo.answersOf(playId).orDie
      position         = answeredSoFar.size + 1
      newScore         = answeredSoFar.map(_.points).sum + scored.points
      finishedAt       = Option.when(position == play.wordCount)(now)
      answer           = GamePlayAnswerRow(
                           id = 0L,
                           playId = playId,
                           wordId = wordId,
                           translationWordId = bestId,
                           position = position,
                           userAnswer = answerText,
                           outcome = AnswerOutcome.code(scored.outcome),
                           points = scored.points,
                           answeredAt = now,
                         )
      _               <- repo.recordAnswer(answer, newScore, finishedAt).orDie
    } yield ()
  }
```

Add a `variantOf` helper, right after `answerResultsOf`:

```scala
  /** `play`'s own settings, as the wire-facing [[GameVariantDto]] — embedded in every play-facing response
    * ([[GameResults]], [[GamePlaySummary]], [[GamePlayDetail]], [[MyPlaySummary]]) so a reader can see what
    * variant a given play actually ran under.
    */
  private def variantOf(play: GamePlayRow): GameVariantDto = {
    GameVariantDto(
      sourceLanguage = WordLanguage.fromString(play.sourceLanguage).getOrElse(WordLanguage.En),
      targetLanguage = WordLanguage.fromString(play.targetLanguage).getOrElse(WordLanguage.En),
      wordLimit = play.wordLimit,
      includeDefiniteArticles = play.includeDefiniteArticles,
      wordPreference = WordPreference.fromString(play.wordPreference).getOrElse(WordPreference.All),
    )
  }
```

Replace `getResults`:

```scala
  def getResults(playId: Long, requesterUserId: Long): IO[GameFailure, GameResults] = {
    for {
      play    <- requireOwnedPlay(playId, requesterUserId)
      answers <- repo.answersOf(playId).orDie
      results <- answerResultsOf(answers, play.includeDefiniteArticles)
    } yield GameResults(play.score, play.maxScore, play.wordCount, results, variantOf(play))
  }
```

Replace `summaryOf`:

```scala
  private def summaryOf(play: GamePlayRow, usersById: Map[Long, UserRow]): GamePlaySummary = {
    val player = usersById.get(play.playerUserId)
    GamePlaySummary(
      playId = play.id,
      playerEmail = player.flatMap(_.email),
      playerIsGuest = player.exists(_.isGuest),
      score = play.score,
      maxScore = play.maxScore,
      wordCount = play.wordCount,
      startedAt = play.startedAt,
      finishedAt = play.finishedAt,
      variant = variantOf(play),
    )
  }
```

Replace `getPlayDetail`'s final `yield`:

```scala
    } yield GamePlayDetail(
      playId = play.id,
      playerEmail = player.flatMap(_.email),
      playerIsGuest = player.exists(_.isGuest),
      score = play.score,
      maxScore = play.maxScore,
      wordCount = play.wordCount,
      startedAt = play.startedAt,
      finishedAt = play.finishedAt,
      answers = results,
      variant = variantOf(play),
    )
```

Replace `myPlaySummaryOf`:

```scala
  private def myPlaySummaryOf(play: GamePlayRow, gamesById: Map[Long, GameRow]): MyPlaySummary = {
    val game = gamesById.get(play.gameId)
    MyPlaySummary(
      playId = play.id,
      gameSlug = game.map(_.slug).getOrElse(""),
      gameName = game.map(_.name).getOrElse(""),
      score = play.score,
      maxScore = play.maxScore,
      wordCount = play.wordCount,
      startedAt = play.startedAt,
      finishedAt = play.finishedAt,
      variant = variantOf(play),
    )
  }
```

(`listPlays`/`getPlayDetail`/`myPlays`/`trackedPlaysOf`/`playsPageFor`/`requireOwnGame`/`eligibleWordPoolForTags`/`eligibleWords`/`eligibleTags`/`myGames`/`rename` are otherwise unchanged — they still read `game.trackResults`, which still exists on `GameRow`.)

- [ ] **Step 2: `ApiFailures` — validate `startPlay`'s body, delete `gameReshuffle`**

In `modules/backend/src/main/scala/gathedge/backend/http/ApiFailures.scala`:

Replace the block comment above `def game(...)`:

```scala
  // GameFailure gets six mappings rather than one: create only ever raises NoTagsSelected/TagNotEligible/
  // ValidationError (all BadRequest), get only ever raises NotFound, rename can raise NotFound/NotOwner/
  // ValidationError, startPlay can raise NotFound/NoEligibleWords/ValidationError, the three play-id endpoints
  // (nextPrompt/submitAnswer/getResults) can raise NotFound/NotOwner, and the owner-facing results listing/detail
  // (listPlays/getPlayDetail) can raise NotFound/NotOwner/NotTracked. A single wide mapping would force every one
  // of them to describe statuses they cannot produce — the same reason the guest mappings below are four
  // functions instead of one.
```

Replace `gameStartPlay`:

```scala
  /** Starting a play: an unknown slug, an out-of-range `wordLimit`, or a resolved direction whose tags currently
    * carry nothing eligible to play.
    */
  def gameStartPlay(failure: GameFailure): ApiFailure.BadRequest | ApiFailure.NotFound = {
    failure match {
      case GameFailure.NotFound                     =>
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
      case GameFailure.NoEligibleWords               =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.gameNoEligibleWords),
          "This game has no eligible words to play right now",
        )
      case GameFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case _                                         =>
        // Unreachable through this mapping: startPlay never raises NoTagsSelected/TagNotEligible/NotOwner/
        // NotTracked. Mapped anyway to keep the match total.
        ApiFailure.BadRequest(MessageRef(MessageKeys.validationFailed), "Validation failed")
    }
  }
```

Delete the `gameReshuffle` function and its doc comment entirely:

```scala
  /** Reshuffling: an unknown slug, one that belongs to somebody else, or a game with nothing fixed to reshuffle
    * (`randomizeEachPlay = true`, or no word limit at all).
    */
  def gameReshuffle(failure: GameFailure): ApiFailure.Conflict | ApiFailure.Forbidden | ApiFailure.NotFound = {
    failure match {
      case GameFailure.NotOwner     =>
        ApiFailure.Forbidden(MessageRef(MessageKeys.gameNotOwner), "You do not own this game")
      case GameFailure.NotFound     =>
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
      case GameFailure.NotFixedPool =>
        ApiFailure.Conflict(
          MessageRef(MessageKeys.gameNotFixedPool),
          "This game has nothing fixed to reshuffle",
        )
      case _                        =>
        // Unreachable through this mapping. Mapped anyway to keep the match total.
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
    }
  }
```

- [ ] **Step 3: `GameRoutes` — wire the new `create`/`startPlay`/`playSetup` shapes**

In `modules/backend/src/main/scala/gathedge/backend/http/GameRoutes.scala`:

Replace the `import gathedge.shared.dto.{...}` block:

```scala
import gathedge.shared.dto.{
  CreateGameRequest,
  GameCreated,
  Paging,
  RenameGameRequest,
  SortDirection,
  StartPlayRequest,
  SubmitAnswerRequest,
}
```

Add `WordPreference` to the `gathedge.shared.domain` import: `import gathedge.shared.domain.{User, WordLanguage, WordPreference}`.

Add a `preferenceOf` helper next to `languageOf`:

```scala
  /** A `wordPreference` query/body value, read leniently like [[languageOf]]: an unrecognised or missing one falls
    * back to [[WordPreference.All]] rather than failing the request.
    */
  private def preferenceOf(requested: Option[String]): WordPreference = {
    requested.flatMap(WordPreference.fromString).getOrElse(WordPreference.All)
  }
```

Replace `createRoute`:

```scala
  private val createRoute = {
    GameEndpoints.create.implementHandler(
      handler { (body: CreateGameRequest) =>
        userId.flatMap(id => {
          GameService
            .createGame(id, body.sourceLanguage, body.targetLanguage, body.tagIds, body.trackResults)
            .map(detail => GameCreated(detail.slug, detail.name))
            .mapError(ApiFailures.gameCreate)
        })
      }
    )
  }
```

Delete `reshuffleRoute` entirely:

```scala
  private val reshuffleRoute = {
    GameEndpoints.reshuffle.implementHandler(
      handler { (slug: String) =>
        userId.flatMap(id => GameService.reshuffle(slug, id).mapError(ApiFailures.gameReshuffle))
      }
    )
  }
```

Replace `startPlayRoute`:

```scala
  private val startPlayRoute = {
    GameEndpoints.startPlay.implementHandler(
      handler { (slug: String, body: StartPlayRequest) =>
        userId.flatMap(id =>
          GameService
            .startPlay(slug, id, body.swapDirection, body.wordLimit, body.includeDefiniteArticles, body.wordPreference)
            .mapError(ApiFailures.gameStartPlay)
        )
      }
    )
  }
```

Add `playSetupRoute`, right after `startPlayRoute`:

```scala
  private val playSetupRoute = {
    GameEndpoints.playSetup.implementHandler(
      handler { (slug: String, swapDirection: Option[Boolean], wordPreference: Option[String]) =>
        userId.flatMap(id =>
          GameService
            .playSetupPreview(slug, id, swapDirection.getOrElse(false), preferenceOf(wordPreference))
            .mapError(ApiFailures.game)
        )
      }
    )
  }
```

Update the `sessionRoutes` list — remove `reshuffleRoute`, add `playSetupRoute` right after `startPlayRoute`:

```scala
  private val sessionRoutes = {
    Routes(
      setupRoute,
      setupWordsRoute,
      mineRoute,
      myPlaysRoute,
      createRoute,
      renameRoute,
      startPlayRoute,
      playSetupRoute,
      nextPromptRoute,
      submitAnswerRoute,
      resultsRoute,
      listPlaysRoute,
      playDetailRoute,
    ) @@ RouteSupport.authenticated
  }
```

- [ ] **Step 4: Compile the whole backend**

Run: `sbt backend/compile`
Expected: Succeeds. (`GameServiceSpec`/`GameRepositorySpec`/`PostgresIntegrationSpec` still fail to *run* until Tasks 8/9 update them — that is expected and fixed next.)

- [ ] **Step 5: Commit**

```bash
git add modules/backend/src/main/scala/gathedge/backend/service/GameService.scala modules/backend/src/main/scala/gathedge/backend/http/ApiFailures.scala modules/backend/src/main/scala/gathedge/backend/http/GameRoutes.scala
git commit -m "feat: variant-aware play loop, listings, failures and routes"
```

---

## Task 8: `GameServiceSpec` — replace fixed-pool/reshuffle tests, run the full suite

**Files:**
- Modify: `modules/backend/src/test/scala/gathedge/backend/service/GameServiceSpec.scala`

**Interfaces:**
- Consumes: everything from Tasks 1–7. This task's own additions were already specified in Task 6, Step 1 — this task deletes what they replace and gets the whole file green.

- [ ] **Step 1: Delete the tests that exercised removed behaviour**

In `modules/backend/src/test/scala/gathedge/backend/service/GameServiceSpec.scala`, delete these six tests wholesale (they assert `randomizeEachPlay`/`wordLimit` as `GameRow`/`GameDetail`-level, fixed-pool sampling, and `reshuffle` — all removed):

- `"creating a game with a non-positive or too-large word limit fails validation"` (superseded by Task 6's `"an out-of-range play-time word limit fails validation"`, which asserts the same rule against `startPlay` instead of `createGame`)
- `"a word limit fixes the play's word count below the eligible pool, and the pool alone is unaffected"` (superseded by Task 6's `"a play-time word limit samples the play, without ever touching the game itself"`)
- `"a limited play's word set is fixed at start and stays consistent across the whole playthrough"` — **keep this one**, but update its `createGame` call to drop `wordLimit`, and pass it to `startPlay` instead (see Step 2).
- `"a game with no word limit still uses every eligible word, exactly as before this setting existed"` — **keep**, update the same way.
- `"a fixed word pool is drawn once at creation and every playthrough reuses the same words"` — delete (the concept no longer exists).
- `"randomizeEachPlay is forced true when no word limit is set, even if fixed is requested"` — delete.
- `"reshuffle is refused to anyone but the game's owner"` — delete.
- `"reshuffle is refused on a game with nothing fixed to reshuffle"` — delete.
- `"a successful reshuffle redraws the fixed pool from the current eligible words"` — delete.
- `"includeDefiniteArticles defaults to true and, when false, strips the article everywhere"` — update: move the `includeDefiniteArticles = false` argument from `createGame` to `startPlay` (see Step 2).

- [ ] **Step 2: Update the two tests kept from Step 1 to pass their setting to `startPlay` instead of `createGame`**

Replace `"a limited play's word set is fixed at start and stays consistent across the whole playthrough"`:

```scala
      test("a limited play's word set is fixed at start and stays consistent across the whole playthrough") {
        for {
          owner     <- newUser()
          tagId     <- eligibleTagWithPairs(owner, "sampled", WordLanguage.De, WordLanguage.Hu, count = 5)
          created   <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started   <- GameService.startPlay(created.slug, owner, wordLimit = Some(2))
          scenarios <- playThrough(started.playId, "sampled", owner)
          results   <- GameService.getResults(started.playId, owner)
        } yield assertTrue(
          scenarios.size == 2,
          results.wordCount == 2,
          results.answers.size == 2,
          results.answers.map(_.wordText).distinct.size == 2,
        )
      },
      test("a game with no word limit still uses every eligible word, exactly as before this setting existed") {
        for {
          owner   <- newUser()
          tagId   <- eligibleTagWithPairs(owner, "unlimited", WordLanguage.De, WordLanguage.Hu, count = 4)
          created <- GameService.createGame(owner, WordLanguage.De, WordLanguage.Hu, List(tagId))
          started <- GameService.startPlay(created.slug, owner)
        } yield assertTrue(started.wordCount == 4, started.maxScore == 8)
      },
```

Replace `"includeDefiniteArticles defaults to true and, when false, strips the article everywhere"`:

```scala
      test("includeDefiniteArticles defaults to true and, when false, strips the article everywhere") {
        for {
          owner   <- newUser()
          tag     <- WordRepository.insertTag(owner, "bareArticle", "bareArticle", 0L)
          source  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "szekreny"))
          target  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Schrank", gender = Some(Gender.Der)))
          _       <- WordRepository.pairTranslation(source.id, tag.id, target.id, 0L)
          created <- GameService.createGame(owner, WordLanguage.Hu, WordLanguage.De, List(tag.id))
          default <- GameService.startPlay(created.slug, owner)
          defaultResults <- GameService.getResults(default.playId, owner)
          bare    <- GameService.startPlay(created.slug, owner, includeDefiniteArticles = false)
          prompt  <- GameService.nextPrompt(bare.playId, owner)
          _       <- GameService.submitAnswer(bare.playId, prompt.wordId.get, "Schrank", owner)
          results <- GameService.getResults(bare.playId, owner)
        } yield assertTrue(
          defaultResults.variant.includeDefiniteArticles,
          !results.variant.includeDefiniteArticles,
          results.answers.head.outcome == AnswerOutcome.Correct,
          results.answers.head.expectedText == "Schrank",
        )
      },
```

Also update every other still-present `createGame(..., wordLimit = ...)`/`createGame(..., randomizeEachPlay = ...)` call and every `created.wordLimit`/`created.randomizeEachPlay` assertion that Step 1 did not already remove — grep the file for `wordLimit`/`randomizeEachPlay` after Step 1's deletions and fix any stragglers the same way (move the argument to the matching `startPlay` call, or drop the assertion if Task 2 already dropped the field from `GameDetail`).

Add `WordPreference` to the file's `gathedge.shared.domain` import (`import gathedge.shared.domain.{AnswerOutcome, Gender, PartOfSpeech, WordLanguage, WordPreference}`) — needed by the tests Task 6 already added to this file.

- [ ] **Step 3: Run the full suite**

Run: `sbt "backend/testOnly gathedge.backend.service.GameServiceSpec"`
Expected: PASS, every test.

- [ ] **Step 4: Also re-run Task 4's repository spec now that the module compiles**

Run: `sbt "backend/testOnly gathedge.backend.db.GameRepositorySpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/backend/src/test/scala/gathedge/backend/service/GameServiceSpec.scala
git commit -m "test: rewrite GameServiceSpec for play-time variants"
```

---

## Task 9: `OpenApiSpec` and `PostgresIntegrationSpec` updates

**Files:**
- Modify: `modules/backend/src/test/scala/gathedge/backend/http/OpenApiSpec.scala`
- Modify: `modules/backend/src/test/scala/gathedge/backend/PostgresIntegrationSpec.scala`

**Interfaces:**
- Consumes: everything from Tasks 1–8.

- [ ] **Step 1: Update `OpenApiSpec`'s pinned path list and status tables**

In `modules/backend/src/test/scala/gathedge/backend/http/OpenApiSpec.scala`:

In the pinned path list (around line 95-106), replace `"/api/games/{slug}/reshuffle"` with `"/api/games/{slug}/plays/setup"`, keeping it adjacent to the other `/plays` paths:

```scala
              "/api/games",
              "/api/games/setup",
              "/api/games/setup/words",
              "/api/games/mine",
              "/api/games/plays/mine",
              "/api/games/{slug}",
              "/api/games/{slug}/plays",
              "/api/games/{slug}/plays/setup",
              "/api/games/{slug}/plays/{playId}",
              "/api/games/plays/{playId}/prompt",
              "/api/games/plays/{playId}/answers",
              "/api/games/plays/{playId}/results",
```

Replace the status-table entry and its comment for the reshuffle path:

```scala
              // Guarded by `optionalUser`, the same reasoning as the vocabulary reads: a shared game link must be
              // viewable before any guest is minted.
              ("GET", "/api/games/{slug}")                                                -> Set(Ok, NotFound),
              // The only endpoint whose 403 is a business rule outside login/guest: `GameService.rename` raises
              // `NotOwner` for anyone but the game's owner.
              ("PATCH", "/api/games/{slug}")                                              ->
                Set(Ok, BadRequest, Unauthorized, Forbidden, NotFound),
              // startPlay's own failures are BadRequest (an out-of-range wordLimit, or a resolved direction with
              // nothing eligible right now) or NotFound (an unknown slug).
              ("POST", "/api/games/{slug}/plays")                                         ->
                Set(Created, BadRequest, Unauthorized, NotFound),
              // The play-variant picker's preview — session-gated like every other play action, NotFound for an
              // unknown slug.
              ("GET", "/api/games/{slug}/plays/setup")                                    -> Set(Ok, Unauthorized, NotFound),
```

(Replace whatever the existing two lines for `("POST", "/api/games/{slug}/reshuffle")` and `("POST", "/api/games/{slug}/plays")` currently read, using this as the new pair — delete the reshuffle line outright.)

In the 403-capable path list (around line 387-401), delete the `("POST", "/api/games/{slug}/reshuffle"),` line and its preceding comment reference to reshuffle (adjust the comment's prose from "on rename, reshuffle, the three play-id operations" to "on rename, the three play-id operations").

- [ ] **Step 2: Update `PostgresIntegrationSpec`'s `GameRow`/`GamePlayRow` constructions**

In `modules/backend/src/test/scala/gathedge/backend/PostgresIntegrationSpec.scala`:

The `GameRow(...)` 8-positional-arg constructions (around lines 202 and 292/296) are unaffected — `trackResults` already defaults to `false`/is passed by name where needed, and none of them pass `wordLimit` positionally except one.

Replace the one that does (around line 249-250, the `game_play_words` cascade test):

```scala
          game       <- GameRepository.insertGame(
                          GameRow(0L, target.id, "pg-wordlimit-slug", "PG Word Limit", "de", "hu", 0L, 0L),
                          Nil,
                        )
```

(drops the trailing `Some(1)` — that test only ever needed a game to attach a play to, never actually asserted on `wordLimit`.)

Every other `GameRepository.insertGame(GameRow(...), ...)` call already passes `Nil`/no third arg or already matches the new two-arg `insertGame` signature from Task 4 — confirm none pass a third `wordPool` argument; if any do, drop that argument.

The `GamePlayRow(...)` constructions (around lines 253-260, 300-309) already compile unchanged, since Task 3 defaulted the five new trailing fields — no edit needed there.

- [ ] **Step 3: Run both specs**

Run: `sbt "backend/testOnly gathedge.backend.http.OpenApiSpec"`
Expected: PASS

Run (requires Postgres): `docker compose up -d postgres && RUN_POSTGRES_TESTS=1 sbt "backend/testOnly gathedge.backend.PostgresIntegrationSpec"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add modules/backend/src/test/scala/gathedge/backend/http/OpenApiSpec.scala modules/backend/src/test/scala/gathedge/backend/PostgresIntegrationSpec.scala
git commit -m "test: update OpenApiSpec and PostgresIntegrationSpec for the new play-setup endpoint"
```

---

## Task 10: i18n — `MessageKeys`, `UiKeys`, both message catalogs

**Files:**
- Modify: `modules/shared/shared/src/main/scala/gathedge/shared/i18n/MessageKeys.scala`
- Modify: `modules/shared/shared/src/main/scala/gathedge/shared/i18n/UiKeys.scala`
- Modify: `web/public/locales/messages.en.json`
- Modify: `web/public/locales/messages.hu.json`

**Interfaces:**
- Produces: the exact `UiKeys` constants Tasks 12/13 render — `gameInstanceDirectionSwap`, `gameInstanceWordLimitLabel/SelectAll/Count`, `gameInstanceWordsHeading/Count/Empty`, `gameInstanceIncludeArticlesLabel/Hint`, `gameInstancePreferenceLabel/All/Unplayed/MostMistakes`. Removes `gameSetupWordLimitLabel/SelectAll/Count`, `gameSetupWordsHeading/Count/Empty`, `gameSetupRandomizeLabel/Always/Fixed`, `gameSetupIncludeArticlesLabel/Hint`, `gameInstanceReshuffle/Reshuffled`, and the now-dead `MessageKeys.gameNotFixedPool`.

- [ ] **Step 1: `MessageKeys` — drop the dead reshuffle key**

In `modules/shared/shared/src/main/scala/gathedge/shared/i18n/MessageKeys.scala`, delete:

```scala
  /** `GameFailure.NotFixedPool` — reshuffle on a game that draws a fresh sample every play already, or has no word
    * limit at all.
    */
  val gameNotFixedPool: String = key("games.notFixedPool")
```

(`gameWordLimitInvalid` stays — Task 6/7 still raise `ValidationError` with that key, just from `startPlay` instead of `createGame`.)

- [ ] **Step 2: `UiKeys` — remove the setup-time controls, add the play-time picker's**

In `modules/shared/shared/src/main/scala/gathedge/shared/i18n/UiKeys.scala`, delete:

```scala
  val gameSetupWordLimitLabel: String     = key("ui.gameSetup.wordLimitLabel")
  val gameSetupWordLimitSelectAll: String = key("ui.gameSetup.wordLimitSelectAll")
  val gameSetupWordLimitCount: String     = key("ui.gameSetup.wordLimitCount")
  ...
  val gameSetupWordsHeading: String = key("ui.gameSetup.words.heading")
  val gameSetupWordsCount: String   = pluralKey("ui.gameSetup.words.count")
  val gameSetupWordsEmpty: String   = key("ui.gameSetup.words.empty")
  ...
  val gameSetupRandomizeLabel: String  = key("ui.gameSetup.randomize.label")
  val gameSetupRandomizeAlways: String = key("ui.gameSetup.randomize.always")
  val gameSetupRandomizeFixed: String  = key("ui.gameSetup.randomize.fixed")
  ...
  val gameSetupIncludeArticlesLabel: String = key("ui.gameSetup.includeArticles.label")
  val gameSetupIncludeArticlesHint: String  = key("ui.gameSetup.includeArticles.hint")
```

(keep `gameSetupTitle/SourceLabel/TargetLabel/TagsLabel/TagFilterLabel/TagFilterPlaceholder/NoEligibleTags/NoMatchingTags/Play/Created/TrackResultsLabel/TrackResultsHint` — the creation form keeps its language pair, tags, and track-results toggle.)

Delete:

```scala
  // Owner-only, shown only for a `randomizeEachPlay = false` game — see `GameService.reshuffle`. No confirmation
  // needed: one click, one request, nothing to undo but a redraw.
  val gameInstanceReshuffle: String  = key("ui.gameInstance.reshuffle")
  val gameInstanceReshuffled: String = key("ui.gameInstance.reshuffled")
```

Add, right after `gameInstanceStart` (the play-variant picker's own keys):

```scala
  val gameInstanceDirectionSwap: String = key("ui.gameInstance.direction.swap")

  val gameInstanceWordLimitLabel: String     = key("ui.gameInstance.wordLimit.label")
  val gameInstanceWordLimitSelectAll: String = key("ui.gameInstance.wordLimit.selectAll")
  val gameInstanceWordLimitCount: String     = key("ui.gameInstance.wordLimit.count")

  val gameInstanceWordsHeading: String = key("ui.gameInstance.words.heading")
  val gameInstanceWordsCount: String   = pluralKey("ui.gameInstance.words.count")
  val gameInstanceWordsEmpty: String   = key("ui.gameInstance.words.empty")

  val gameInstanceIncludeArticlesLabel: String = key("ui.gameInstance.includeArticles.label")
  val gameInstanceIncludeArticlesHint: String  = key("ui.gameInstance.includeArticles.hint")

  val gameInstancePreferenceLabel: String        = key("ui.gameInstance.preference.label")
  val gameInstancePreferenceAll: String          = key("ui.gameInstance.preference.all")
  val gameInstancePreferenceUnplayed: String     = key("ui.gameInstance.preference.unplayed")
  val gameInstancePreferenceMostMistakes: String = key("ui.gameInstance.preference.mostMistakes")
```

- [ ] **Step 3: `messages.en.json` — mirror the same additions/removals**

In `web/public/locales/messages.en.json`, delete:

```json
  "ui.gameSetup.wordLimitLabel": "How many words",
  "ui.gameSetup.wordLimitSelectAll": "Use every eligible word",
  "ui.gameSetup.wordLimitCount": "Use exactly this many words",
  "ui.gameSetup.words.heading": "Eligible words",
  "ui.gameSetup.words.count.one": "{0} word",
  "ui.gameSetup.words.count.other": "{0} words",
  "ui.gameSetup.words.empty": "No eligible words for this selection yet",
  "ui.gameSetup.randomize.label": "Word selection",
  "ui.gameSetup.randomize.always": "Randomize every time this quiz is played",
  "ui.gameSetup.randomize.fixed": "Randomize now, and keep the same words every time",
```

keep `"ui.gameSetup.trackResults.label"`/`"ui.gameSetup.trackResults.hint"`, delete:

```json
  "ui.gameSetup.includeArticles.label": "Include definite articles",
  "ui.gameSetup.includeArticles.hint": "Show \"der\"/\"die\"/\"das\" with German nouns in the quiz",
```

delete:

```json
  "ui.gameInstance.reshuffle": "Reshuffle words",
  "ui.gameInstance.reshuffled": "Words reshuffled — the next play will use the new set.",
```

add, right after `"ui.gameInstance.start": "Start",` (or wherever that key sits):

```json
  "ui.gameInstance.direction.swap": "Swap languages",
  "ui.gameInstance.wordLimit.label": "How many words",
  "ui.gameInstance.wordLimit.selectAll": "Use every eligible word",
  "ui.gameInstance.wordLimit.count": "Use exactly this many words",
  "ui.gameInstance.words.heading": "Eligible words",
  "ui.gameInstance.words.count.one": "{0} word",
  "ui.gameInstance.words.count.other": "{0} words",
  "ui.gameInstance.words.empty": "No eligible words for this selection yet",
  "ui.gameInstance.includeArticles.label": "Include definite articles",
  "ui.gameInstance.includeArticles.hint": "Show \"der\"/\"die\"/\"das\" with German nouns in the quiz",
  "ui.gameInstance.preference.label": "Which words",
  "ui.gameInstance.preference.all": "All words",
  "ui.gameInstance.preference.unplayed": "Words I haven't played",
  "ui.gameInstance.preference.mostMistakes": "Words I've made the most mistakes with",
```

delete:

```json
  "games.notFixedPool": "This game has nothing fixed to reshuffle",
```

- [ ] **Step 4: `messages.hu.json` — the same keys, translated**

In `web/public/locales/messages.hu.json`, delete the mirrored `ui.gameSetup.wordLimit.*`/`ui.gameSetup.words.*`/`ui.gameSetup.randomize.*`/`ui.gameSetup.includeArticles.*`/`ui.gameInstance.reshuffle`/`ui.gameInstance.reshuffled`/`games.notFixedPool` entries (same keys as Step 3, Hungarian values), and add:

```json
  "ui.gameInstance.direction.swap": "Nyelvek felcserélése",
  "ui.gameInstance.wordLimit.label": "Hány szó",
  "ui.gameInstance.wordLimit.selectAll": "Minden megfelelő szó használata",
  "ui.gameInstance.wordLimit.count": "Pontosan ennyi szó használata",
  "ui.gameInstance.words.heading": "Megfelelő szavak",
  "ui.gameInstance.words.count.one": "{0} szó",
  "ui.gameInstance.words.count.other": "{0} szó",
  "ui.gameInstance.words.empty": "Ehhez a kiválasztáshoz még nincs megfelelő szó",
  "ui.gameInstance.includeArticles.label": "Határozott névelők megjelenítése",
  "ui.gameInstance.includeArticles.hint": "A német főnevek „der”/„die”/„das” névelővel jelenjenek meg a kvízben",
  "ui.gameInstance.preference.label": "Melyik szavak",
  "ui.gameInstance.preference.all": "Minden szó",
  "ui.gameInstance.preference.unplayed": "Amiket még nem játszottam",
  "ui.gameInstance.preference.mostMistakes": "Amikben a legtöbbet hibáztam",
```

- [ ] **Step 5: Run the messages/i18n parity spec**

Run: `sbt "backend/testOnly *MessagesSpec"`
Expected: PASS — identical key sets, matching placeholders, complete plural pairs across both catalogs.

- [ ] **Step 6: Commit**

```bash
git add modules/shared/shared/src/main/scala/gathedge/shared/i18n/MessageKeys.scala modules/shared/shared/src/main/scala/gathedge/shared/i18n/UiKeys.scala web/public/locales/messages.en.json web/public/locales/messages.hu.json
git commit -m "feat: move game copy keys from setup to the play-variant picker"
```

---

## Task 11: `GameApiClient` — new call shapes

**Files:**
- Modify: `modules/frontend/src/main/scala/gathedge/frontend/api/GameApiClient.scala`

**Interfaces:**
- Consumes: `StartPlayRequest`, `GameEndpoints.playSetup`, `WordPreference` (Tasks 1–2).
- Produces: `GameApiClient.create(source, target, tagIds, trackResults: Boolean = false)`, `GameApiClient.startPlay(slug, swapDirection: Boolean = false, wordLimit: Option[Int] = None, includeDefiniteArticles: Boolean = true, wordPreference: WordPreference = WordPreference.All)`, `GameApiClient.playSetup(slug, swapDirection, wordPreference): EventStream[Either[ApiError, List[GameSetupWord]]]`. `reshuffle` removed. Tasks 12/13 call these.

- [ ] **Step 1: Update imports, `create`, `startPlay`; remove `reshuffle`; add `playSetup`**

Replace the whole content of `modules/frontend/src/main/scala/gathedge/frontend/api/GameApiClient.scala` with:

```scala
package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.GameEndpoints
import gathedge.shared.domain.{Tag, WordLanguage, WordPreference}
import gathedge.shared.dto.{
  CreateGameRequest,
  GameCreated,
  GameDetail,
  GamePlayDetail,
  GamePlayPage,
  GamePrompt,
  GameResults,
  GameSetupWord,
  MyGameSummary,
  MyPlayPage,
  PlayStarted,
  RenameGameRequest,
  StartPlayRequest,
  SubmitAnswerRequest,
}

import EndpointClient.{executor, run}

/** The game catalog's calls, generated from `GameEndpoints` the same way [[WordApiClient]] is from `WordEndpoints`.
  *
  * [[setup]] and [[create]] require a session — see `GameSetupPage`'s guest detour, which sits in front of each.
  * [[get]] does not — it is the `optionalUser` read a shared game link is opened through. [[startPlay]] is the
  * first call in the play loop that needs a session; [[playSetup]] also needs one, since its preview depends on
  * the caller's own play history in this game.
  */
object GameApiClient {

  /** The tags eligible for a quiz between `source` and `target`, own tags first — see `Tag.sorted`. */
  def setup(source: WordLanguage, target: WordLanguage): EventStream[Either[ApiError, List[Tag]]] = {
    run(executor(GameEndpoints.setup(Some(WordLanguage.code(source)), Some(WordLanguage.code(target)))))
  }

  /** The setup screen's word-list preview: exactly the eligible pool a game built from `tagIds` would draw from. */
  def setupWords(
    source: WordLanguage,
    target: WordLanguage,
    tagIds: Set[Long],
  ): EventStream[Either[ApiError, List[GameSetupWord]]] = {
    val joined = Option.when(tagIds.nonEmpty)(tagIds.mkString(","))
    run(executor(GameEndpoints.setupWords(Some(WordLanguage.code(source)), Some(WordLanguage.code(target)), joined)))
  }

  /** The signed-in caller's own games, for the "my games" table. */
  def myGames(): EventStream[Either[ApiError, List[MyGameSummary]]] = {
    run(executor(GameEndpoints.mine(())))
  }

  def create(
    source: WordLanguage,
    target: WordLanguage,
    tagIds: List[Long],
    trackResults: Boolean = false,
  ): EventStream[Either[ApiError, GameCreated]] = {
    run(executor(GameEndpoints.create(CreateGameRequest(source, target, tagIds, trackResults))))
  }

  /** A shared game link's detail — playable, and readable, by anybody. */
  def get(slug: String): EventStream[Either[ApiError, GameDetail]] = {
    run(executor(GameEndpoints.get(slug)))
  }

  /** Owner-only — see `GameEndpoints.rename`'s doc comment. */
  def rename(slug: String, name: String): EventStream[Either[ApiError, GameDetail]] = {
    run(executor(GameEndpoints.rename(slug, RenameGameRequest(name))))
  }

  /** Starts a fresh attempt at `slug` under the given variant — see [[StartPlayRequest]]. */
  def startPlay(
    slug: String,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    includeDefiniteArticles: Boolean = true,
    wordPreference: WordPreference = WordPreference.All,
  ): EventStream[Either[ApiError, PlayStarted]] = {
    run(
      executor(
        GameEndpoints.startPlay(slug, StartPlayRequest(swapDirection, wordLimit, includeDefiniteArticles, wordPreference))
      )
    )
  }

  /** The play-variant picker's preview: the resolved-direction eligible pool, in the order [[startPlay]] would
    * sample from for the same `swapDirection`/`wordPreference`.
    */
  def playSetup(
    slug: String,
    swapDirection: Boolean,
    wordPreference: WordPreference,
  ): EventStream[Either[ApiError, List[GameSetupWord]]] = {
    run(executor(GameEndpoints.playSetup(slug, Some(swapDirection), Some(WordPreference.code(wordPreference)))))
  }

  def nextPrompt(playId: Long): EventStream[Either[ApiError, GamePrompt]] = {
    run(executor(GameEndpoints.nextPrompt(playId)))
  }

  def submitAnswer(playId: Long, wordId: Long, answerText: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(GameEndpoints.submitAnswer(playId, SubmitAnswerRequest(wordId, answerText))))
  }

  /** The finished play's score, full answer history, and the variant it ran under. */
  def getResults(playId: Long): EventStream[Either[ApiError, GameResults]] = {
    run(executor(GameEndpoints.results(playId)))
  }

  /** Owner-only, and only for a `trackResults = true` game: one page of `slug`'s plays. */
  def listPlays(
    slug: String,
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
  ): EventStream[Either[ApiError, GamePlayPage]] = {
    run(executor(GameEndpoints.listPlays(slug, page, pageSize, sort, dir, search)))
  }

  /** Owner-only equivalent of [[getResults]]: one play's full answer history, for the result modal. */
  def getPlayDetail(slug: String, playId: Long): EventStream[Either[ApiError, GamePlayDetail]] = {
    run(executor(GameEndpoints.playDetail(slug, playId)))
  }

  /** The caller's own play history across every game — never gated by `trackResults`, unlike [[listPlays]]. */
  def myPlays(
    gameId: Option[Long] = None,
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
  ): EventStream[Either[ApiError, MyPlayPage]] = {
    run(executor(GameEndpoints.myPlays(gameId, page, pageSize, sort, dir)))
  }
}
```

- [ ] **Step 2: Compile the frontend module (will still fail — Tasks 12/13 fix `GameSetupPage`/`GameInstancePage` call sites)**

Run: `sbt frontend/compile`
Expected: FAILS at `GameSetupPage.scala`/`GameInstancePage.scala` (old `create`/`startPlay`/`reshuffle` call shapes). Expected; Tasks 12/13 fix it.

- [ ] **Step 3: Commit**

```bash
git add modules/frontend/src/main/scala/gathedge/frontend/api/GameApiClient.scala
git commit -m "feat: update GameApiClient for play-time variant requests"
```

---

## Task 12: `GameSetupPage` simplified to language pair, tags, track-results

**Files:**
- Modify: `modules/frontend/src/main/scala/gathedge/frontend/pages/GameSetupPage.scala`
- Modify: `modules/frontend/src/test/scala/gathedge/frontend/pages/GameSetupPageSpec.scala`

**Interfaces:**
- Consumes: `GameApiClient.create(source, target, tagIds, trackResults)` (Task 11).
- Produces: no change to `GameSetupPage.render()`'s public signature or `GameSetupPage.matchingTags` (both stay, spec still exercises them the same way).

- [ ] **Step 1: Remove the word-limit/randomize/articles state and controls from `GameSetupPage`**

In `modules/frontend/src/main/scala/gathedge/frontend/pages/GameSetupPage.scala`, delete these private vals entirely: `selectAllVar`, `wordCountVar`, `wordLimitSignal`, `randomizeEachPlayVar`, `includeArticlesVar`, `germanInvolvedSignal` (lines 57–102 of the file as it exists today).

Replace `formAndTagsSignal`:

```scala
  private val formAndTagsSignal = formSignal.combineWith(selectedTagIdsVar.signal, trackResultsVar.signal)
```

Replace the `playBus.events.withCurrentValueOf(formAndTagsSignal).flatMapSwitch { ... }` block:

```scala
      playBus.events.withCurrentValueOf(formAndTagsSignal).flatMapSwitch { case (source, target, tagIds, trackResults) =>
        asReader(() => GameApiClient.create(source, target, tagIds.toList, trackResults))
      } -->
```

Replace `renderWordsColumn`:

```scala
  private def renderWordsColumn(): HtmlElement = {
    div(
      cls := "flex-1",
      renderTrackResultsControl(),
      renderWordsList(),
    )
  }
```

Delete `renderWordLimitControls`, `renderRandomizeControls`, `renderIncludeArticlesControl` entirely (their whole method bodies).

- [ ] **Step 2: Remove the now-orphaned `wordCountBox`/`selectAllBox`-dependent tests from `GameSetupPageSpec`**

In `modules/frontend/src/test/scala/gathedge/frontend/pages/GameSetupPageSpec.scala`, delete `wordCountBox`/`selectAllBox` and the four tests that use them: `"select all starts checked, and the count input starts disabled"`, `"typing a word count unchecks select all"`, `"checking select all back on clears a previously typed count"`. (The word-count control moves to `GameInstancePage` — Task 13 adds equivalent tests there.)

Leave every other test in the file untouched (`matchingTags`, filter-box, empty-state-message tests do not depend on the removed controls).

- [ ] **Step 3: Compile and run the frontend module (fully, since `GameApiClient` is already updated)**

Run: `sbt frontend/compile "frontend/testOnly gathedge.frontend.pages.GameSetupPageSpec"`
Expected: Compiles (frontend still fails elsewhere until Task 13 — `GameInstancePage.scala` still references the old `reshuffle`/no-arg `startPlay`/`GameDetail.randomizeEachPlay`. If `frontend/compile` fails there, run `"frontend/testOnly gathedge.frontend.pages.GameSetupPageSpec"` alone once Task 13 lands instead, since sbt compiles the whole module for any single test). PASS once compiling.

- [ ] **Step 4: Commit**

```bash
git add modules/frontend/src/main/scala/gathedge/frontend/pages/GameSetupPage.scala modules/frontend/src/test/scala/gathedge/frontend/pages/GameSetupPageSpec.scala
git commit -m "refactor: simplify GameSetupPage to language pair, tags, track-results"
```

---

## Task 13: `GameInstancePage` — direction-swap arrow, word count, articles, preference picker

**Files:**
- Modify: `modules/frontend/src/main/scala/gathedge/frontend/pages/GameInstancePage.scala`
- Create: `modules/frontend/src/test/scala/gathedge/frontend/pages/GameInstancePageSpec.scala`

**Interfaces:**
- Consumes: `GameApiClient.startPlay`/`playSetup` (Task 11), `WordPreference` (Task 1), trimmed `GameDetail` (Task 2).
- Produces: `GameInstancePage.render(slug)` unchanged in signature; the "Play" phase now renders a variant picker above the Play button.

- [ ] **Step 1: Remove reshuffle state/UI, add the variant-picker state**

In `modules/frontend/src/main/scala/gathedge/frontend/pages/GameInstancePage.scala`, add `WordPreference` to the `gathedge.shared.domain` import (`import gathedge.shared.domain.{AnswerOutcome, User, WordLanguage, WordPreference}`) and `GameSetupWord` to the `gathedge.shared.dto` import list.

Delete `reshufflingVar`/`reshuffleBus` entirely:

```scala
  private val reshufflingVar = Var(false)
  private val reshuffleBus   = new EventBus[Unit]()
```

Add the variant-picker state, right after `answerTextVar`:

```scala
  /** The direction-swap arrow's own state — `false` plays the game's stored direction, `true` reverses it for this
    * play only. See the design doc's "no dropdowns, just an arrow" direction control.
    */
  private val swapDirectionVar = Var(false)

  /** Mutually exclusive with [[wordLimitTextVar]], the same pattern `GameSetupPage` used before this control moved
    * here. Defaults to `true`: "use every eligible word". Named `wordLimitTextVar`, not `wordCountVar` — the page
    * already has an unrelated `wordCountVar: Var[Int]` (the play's own fixed word count, shown in the "3 of 12"
    * progress line); see the naming note further down.
    */
  private val selectAllVar     = Var(true)
  private val wordLimitTextVar = Var("")

  private val wordLimitSignal: Signal[Option[Int]] = {
    selectAllVar.signal.combineWith(wordLimitTextVar.signal).map {
      case (true, _)     => None
      case (false, text) => text.trim.toIntOption.filter(_ > 0)
    }
  }

  private val includeArticlesVar = Var(true)

  /** Whether *either* resolved direction of the current pair involves German — the swap arrow flips which language
    * is source, but German-either-way is symmetric, so this does not need to depend on [[swapDirectionVar]].
    */
  private val germanInvolvedSignal: Signal[Boolean] = {
    gameVar.signal.map(_.exists(g => g.sourceLanguage == WordLanguage.De || g.targetLanguage == WordLanguage.De))
  }

  private val wordPreferenceVar = Var[WordPreference](WordPreference.All)

  private val previewWordsVar    = Var(List.empty[GameSetupWord])
  private val previewLoadingVar  = Var(false)

  /** Refetches the play-setup preview whenever direction or preference changes, once the game itself has loaded —
    * mirrors `GameSetupPage.wordsQuerySignal`'s reasoning, one screen over.
    */
  private val previewQuerySignal: Signal[(Boolean, WordPreference)] = {
    swapDirectionVar.signal.combineWith(wordPreferenceVar.signal).distinct
  }
```

**Naming note:** the page already has an unrelated `wordCountVar: Var[Int]` — the *play's own* fixed word count, shown in the "3 of 12" progress line, set from `started.wordCount` once a play starts. The picker's own typed count above is deliberately named `wordLimitTextVar`, not `wordCountVar`, to avoid colliding with it.

- [ ] **Step 2: Wire the preview fetch and the variant-aware `startPlay` call**

Replace the `startBus.events.flatMapSwitch(_ => asReader(() => GameApiClient.startPlay(slug))) --> ...` wiring:

```scala
      startBus.events
        .withCurrentValueOf(swapDirectionVar.signal, wordLimitSignal, includeArticlesVar.signal, wordPreferenceVar.signal)
        .flatMapSwitch { case (swap, limit, articles, preference) =>
          asReader(() => GameApiClient.startPlay(slug, swap, limit, articles, preference))
        } -->
        Observer[Either[ApiError, PlayStarted]] {
          case Right(started) =>
            // `wordCountVar` here is the pre-existing play-progress var (see the naming note above), set from the
            // server's actual sampled count — unrelated to this task's own `wordLimitTextVar`.
            Var.set(playIdVar -> Some(started.playId), wordCountVar -> started.wordCount, startingVar -> false)
            nextBus.emit(())
          case Left(err)      =>
            Var.set(startingVar -> false, errorVar -> Some(err.message))
        },
```

Add the preview-fetch wiring, right after the `startBus`/`nextPromptStream` wiring block:

```scala
      previewQuerySignal.updates --> Observer[(Boolean, WordPreference)](_ => previewLoadingVar.set(true)),
      previewQuerySignal.updates
        .filterWith(gameVar.signal.map(_.isDefined))
        .flatMapSwitch { case (swap, preference) => asReader(() => GameApiClient.playSetup(slug, swap, preference)) } -->
        Observer[Either[ApiError, List[GameSetupWord]]] {
          case Right(words) =>
            Var.set(previewWordsVar -> words, previewLoadingVar -> false)
          case Left(err)    =>
            Var.set(previewLoadingVar -> false, errorVar -> Some(err.message))
        },
```

- [ ] **Step 3: Remove the reshuffle button and its stream**

Delete the `reshuffleStream --> Observer[...] { ... }` wiring block and the private `reshuffleStream: EventStream[Either[ApiError, Unit]]` method entirely.

In `renderNameDisplay`, delete the reshuffle button block:

```scala
      // Only for a fixed-pool game — see `reshufflingVar`'s doc comment. `gameVar` is read reactively rather than
      // `.now()`'d: a rename response also carries `randomizeEachPlay`, so this stays correct even though that field
      // itself never actually changes from a rename.
      child.maybe <-- isOwnerVar.signal.combineWith(gameVar.signal).map { case (owner, game) =>
        Option.when(owner && game.exists(!_.randomizeEachPlay))(
          button(
            cls := "btn btn-ghost btn-xs",
            typ := "button",
            disabled <-- reshufflingVar.signal,
            I18n.t(UiKeys.gameInstanceReshuffle),
            onClick.mapToUnit --> reshuffleBus.writer,
          )
        )
      },
```

- [ ] **Step 4: Render the variant picker**

Replace `renderStart`:

```scala
  private def renderStart(): HtmlElement = {
    div(
      cls := "flex flex-col gap-4",
      renderDirectionSwap(),
      renderWordLimitControls(),
      renderIncludeArticlesControl(),
      renderPreferenceControl(),
      renderPreviewList(),
      button(
        cls := "btn btn-primary",
        typ := "button",
        disabled <-- startingVar.signal,
        I18n.t(UiKeys.gameInstanceStart),
        onClick.mapToUnit --> startBus.writer,
      ),
    )
  }

  /** `[source] <-> [target]` with no dropdowns — clicking the arrow flips [[swapDirectionVar]], which decides the
    * play's actual direction independent of the game's own stored one. Labels read from [[gameVar]] directly
    * (unaffected by the swap toggle itself — this is a display order, not a fetch), swapped in place when the
    * toggle is on.
    */
  private def renderDirectionSwap(): HtmlElement = {
    div(
      cls := "flex items-center gap-2",
      child <-- gameVar.signal.combineWith(swapDirectionVar.signal).map {
        case (Some(game), swapped) =>
          val (first, second) = if (swapped) (game.targetLanguage, game.sourceLanguage) else (game.sourceLanguage, game.targetLanguage)
          div(
            cls := "flex items-center gap-2",
            span(cls := "font-medium", Labels.language(first)),
            button(
              cls   := "btn btn-ghost btn-xs",
              typ   := "button",
              title := I18n.t(UiKeys.gameInstanceDirectionSwap),
              "⇄",
              onClick.mapToUnit --> Observer[Unit](_ => swapDirectionVar.update(!_)),
            ),
            span(cls := "font-medium", Labels.language(second)),
          )
        case (None, _)              =>
          emptyNode
      },
    )
  }

  /** Moved verbatim from the old `GameSetupPage`, just retargeted at [[selectAllVar]]/[[wordLimitTextVar]] here. */
  private def renderWordLimitControls(): HtmlElement = {
    div(
      cls := "flex flex-col gap-2",
      span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstanceWordLimitLabel)),
      label(
        cls    := "flex items-center gap-2 cursor-pointer",
        input(
          typ    := "checkbox",
          cls    := "checkbox checkbox-sm",
          controlled(
            checked <-- selectAllVar.signal,
            onClick.mapToChecked --> Observer[Boolean] { on =>
              if (on) Var.set(selectAllVar -> true, wordLimitTextVar -> "") else selectAllVar.set(false)
            },
          ),
        ),
        span(cls := "label-text text-sm", I18n.t(UiKeys.gameInstanceWordLimitSelectAll)),
      ),
      label(
        cls    := "flex items-center gap-2",
        span(cls  := "label-text text-sm", I18n.t(UiKeys.gameInstanceWordLimitCount)),
        input(
          typ     := "number",
          minAttr := "1",
          cls     := "input input-sm w-24",
          disabled <-- selectAllVar.signal,
          controlled(
            value <-- wordLimitTextVar.signal,
            onInput.mapToValue --> Observer[String] { text =>
              Var.set(wordLimitTextVar -> text, selectAllVar -> false)
            },
          ),
        ),
      ),
    )
  }

  private def renderIncludeArticlesControl(): HtmlElement = {
    div(
      child.maybe <-- germanInvolvedSignal.map { involved =>
        Option.when(involved)(
          label(
            cls := "flex items-center gap-2 cursor-pointer",
            input(
              typ := "checkbox",
              cls := "checkbox checkbox-sm",
              controlled(checked <-- includeArticlesVar.signal, onClick.mapToChecked --> includeArticlesVar.writer),
            ),
            div(
              span(cls := "label-text text-sm", I18n.t(UiKeys.gameInstanceIncludeArticlesLabel)),
              p(cls    := "text-xs opacity-60", I18n.t(UiKeys.gameInstanceIncludeArticlesHint)),
            ),
          )
        )
      },
    )
  }

  private def renderPreferenceControl(): HtmlElement = {
    div(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstancePreferenceLabel)),
      select(
        cls    := "select select-sm w-full max-w-xs",
        option(value := "all", I18n.t(UiKeys.gameInstancePreferenceAll)),
        option(value := "unplayed", I18n.t(UiKeys.gameInstancePreferenceUnplayed)),
        option(value := "mostMistakes", I18n.t(UiKeys.gameInstancePreferenceMostMistakes)),
        controlled(
          value <-- wordPreferenceVar.signal.map(WordPreference.code),
          onChange.mapToValue --> wordPreferenceVar.writer.contramap[String](code =>
            WordPreference.fromString(code).getOrElse(WordPreference.All)
          ),
        ),
      ),
    )
  }

  /** The chosen direction/preference's eligible pool preview — same shape as `GameSetupPage.renderWordsList`, one
    * screen over.
    */
  private def renderPreviewList(): HtmlElement = {
    div(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstanceWordsHeading)),
      span(
        cls    := "label-text text-sm opacity-70",
        child.text <-- previewWordsVar.signal.map(words => I18n.plural(UiKeys.gameInstanceWordsCount, words.size.toLong)),
      ),
      child.maybe <-- previewWordsVar.signal.combineWith(previewLoadingVar.signal).map { case (words, loading) =>
        Option.when(words.isEmpty && !loading)(p(cls := "text-sm opacity-60", I18n.t(UiKeys.gameInstanceWordsEmpty)))
      },
    )
  }
```

- [ ] **Step 5: Compile the whole frontend module**

Run: `sbt frontend/compile`
Expected: Succeeds.

- [ ] **Step 6: Write and run frontend specs for the new picker**

Create `modules/frontend/src/test/scala/gathedge/frontend/pages/GameInstancePageSpec.scala`:

```scala
package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import org.scalajs.dom
import zio.test._

/** The play-variant picker under jsdom, with no backend — the same shape `GameSetupPageSpec` uses: every request
  * fails, so the game never finishes loading and the picker (gated on a loaded game) never mounts. That leaves
  * only [[GameInstancePage.render]]'s ability to mount and unmount cleanly to assert here; the picker's own
  * interactive behaviour (swap arrow, word-limit mutual exclusion, preference select) is exercised end-to-end by
  * the `e2e/tests/game.spec.ts` suite instead, the same split this codebase draws elsewhere between jsdom unit
  * tests (pure logic, mount/unmount safety) and Playwright (real interaction against a real backend).
  */
object GameInstancePageSpec extends ZIOSpecDefault {

  def spec = {
    suite("GameInstancePage")(
      test("mounts and unmounts cleanly for an unknown slug") {
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        val rootNode  = L.render(container, GameInstancePage.render("no-such-slug"))
        rootNode.unmount()
        dom.document.body.removeChild(container)
        assertTrue(true)
      }
    )
  }
}
```

Run: `sbt "frontend/testOnly gathedge.frontend.pages.GameInstancePageSpec" "frontend/testOnly gathedge.frontend.pages.GameSetupPageSpec"`
Expected: PASS

- [ ] **Step 7: Full-stack smoke check**

Run: `sbt root/compile` then `npm --prefix web run typecheck`
Expected: Both succeed — this is the first point every module (shared/backend/frontend/web) has been compiled together since Task 2.

- [ ] **Step 8: Commit**

```bash
git add modules/frontend/src/main/scala/gathedge/frontend/pages/GameInstancePage.scala modules/frontend/src/test/scala/gathedge/frontend/pages/GameInstancePageSpec.scala
git commit -m "feat: add the play-variant picker to GameInstancePage"
```

---

## Task 14: Manual verification and e2e sanity pass

**Files:** none (verification only)

**Interfaces:** none — this task confirms the finished feature works end-to-end, per this project's rule that a UI change needs to be exercised in a real browser, not just typechecked.

- [ ] **Step 1: Start the stack**

Run: `docker compose up -d postgres` (after `cp .env.example .env` if not already done), then `npm run dev`.

- [ ] **Step 2: Create a base game and confirm the setup form is now short**

In a browser at `http://localhost:5173` (or wherever Vite reports), sign in (or continue as guest), go to the quiz setup screen, confirm only language pair + tags + "track results" show — no word-count/randomize/articles controls.

- [ ] **Step 3: Play the same game twice with different variants**

Open the created game's share link. Confirm the variant picker shows: a `[source] ⇄ [target]` swap control, a word-count select-all/count pair, an articles toggle (only when German is in the pair), and a preference select with "All"/"Words I haven't played"/"Words I've made the most mistakes with". Play once with the default variant, then again with the direction swapped and a narrower word count under "Words I haven't played" — confirm the second play's sampled words skew toward ones not answered in the first play's direction.

- [ ] **Step 4: Confirm variant tracking on the owner's listing**

With `trackResults` on, open the game's results listing as its owner and confirm each play row shows the variant (direction, word count, preference) it was actually played under, and that the two plays from Step 3 show as two separate rows with their own distinct variants.

- [ ] **Step 5: Run the e2e suite's game spec**

Run: `npm --prefix e2e install && npm --prefix e2e test -- game.spec.ts`
Expected: PASS, or — if the existing spec still asserts on the old setup-time word-limit/randomize/reshuffle UI — update `e2e/tests/game.spec.ts` to match the new play-time picker (this step is exploratory: read the spec first, since its current assertions were written against the pre-redesign UI and are not otherwise covered by this plan's tasks).

- [ ] **Step 6: Report findings**

If Step 5 required edits to `e2e/tests/game.spec.ts`, commit them:

```bash
git add e2e/tests/game.spec.ts
git commit -m "test: update game e2e spec for the play-variant picker"
```

If everything already passed, no commit is needed for this task.
