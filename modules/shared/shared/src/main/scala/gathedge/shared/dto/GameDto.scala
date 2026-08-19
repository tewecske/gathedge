package gathedge.shared.dto

import gathedge.shared.domain.{AnswerOutcome, WordLanguage}
import zio.json.*

/** What `POST /api/games` needs: the language pair to draw words from, which of the caller's eligible tags to build the
  * game out of, and how many words a play should draw from the resulting eligible pool. `wordLimit = None` means "use
  * every eligible word" — the setup screen's "select all" checkbox, and the only behaviour before this field existed;
  * `Some(n)` means "sample exactly n of them" — see `GameService.startPlay`. `randomizeEachPlay` decides *when* that
  * sample is drawn: `true` (the default, and the only behaviour before this field existed) draws it fresh every time
  * this quiz is played; `false` draws it once, here, and every later playthrough reuses that same fixed set until the
  * owner reshuffles it. Meaningless (and ignored server-side) when `wordLimit` is `None`.
  */
final case class CreateGameRequest(
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagIds: List[Long],
  wordLimit: Option[Int] = None,
  randomizeEachPlay: Boolean = true,
  trackResults: Boolean = false,
) derives JsonCodec

/** `POST /api/games`'s answer: just enough to navigate to the game and show its name — the caller already knows
  * everything else it just sent. The full [[GameDetail]] is a separate `GET`.
  */
final case class GameCreated(slug: String, name: String) derives JsonCodec

final case class RenameGameRequest(name: String) derives JsonCodec

/** One row of `GET /api/games/setup/words`'s answer: the setup screen's preview of exactly the pool a game built from
  * the requested tags and language pair would draw from — `text` already carries a gendered source word's article, the
  * same [[gathedge.shared.domain.Word.displayText]] every prompt/result elsewhere in the game uses. Deduped to one row
  * per source word, the same rule `GameService.eligibleWordPool` applies once a game actually exists.
  */
final case class GameSetupWord(wordId: Long, text: String) derives JsonCodec

/** A game as a caller may see it: no owner-only data, no id — `slug` is what a reader addresses it by. `wordLimit`
  * mirrors [[CreateGameRequest.wordLimit]] — `None` for "every eligible word", `Some(n)` for a fixed sample size —
  * cheap to carry here so a game's own page or listing can show "20 words" instead of staying silent about the setting.
  * `randomizeEachPlay` mirrors [[CreateGameRequest.randomizeEachPlay]] — the instance page uses it to decide whether to
  * offer the owner a reshuffle control.
  */
final case class GameDetail(
  slug: String,
  name: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagNames: List[String],
  wordLimit: Option[Int] = None,
  randomizeEachPlay: Boolean = true,
  trackResults: Boolean = false,
) derives JsonCodec

/** `POST /api/games/{slug}/plays`'s answer: enough for the play loop to start — the id every later play call addresses,
  * and the two numbers a progress bar needs (`wordCount` fixed for the whole play, `maxScore` the ceiling if every word
  * is answered correctly).
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

/** `GET /api/games/plays/{playId}/results`'s answer: the finished play's score and its full answer history. */
final case class GameResults(score: Int, maxScore: Int, wordCount: Int, answers: List[GameAnswerResult])
    derives JsonCodec

/** One row of `GET /api/games/mine` — the caller's own games, most recently created first. `playCount` is `0` for a
  * game nobody has played yet, never absent.
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
  * guest who never gave one; `playerIsGuest` lets the table badge that instead of showing a blank cell.
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
) derives JsonCodec

/** One page of a tracked game's plays. `total` counts what matches the player filter, the same rule [[UserPage]]
  * follows.
  */
final case class GamePlayPage(items: List[GamePlaySummary], total: Long) derives JsonCodec

/** `GET /api/games/{slug}/plays/{playId}`'s answer: one player's full attempt, for the owner-facing result modal.
  * Distinct from [[GameResults]], the player-facing equivalent, only by carrying the player's identity — a table row
  * needs to say *whose* result this is.
  */
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
) derives JsonCodec

/** One row of `GET /api/games/plays/mine` (or a shared/admin equivalent): one play, with enough of its game's own
  * identity (`gameSlug`/`gameName`) to render in a listing that spans more than one game — unlike [[GamePlaySummary]],
  * which is already scoped to a single game by the endpoint's own path.
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
) derives JsonCodec

/** One page of a cross-game play history — the player's own, a viewer's shared read, or an admin's. */
final case class MyPlayPage(items: List[MyPlaySummary], total: Long) derives JsonCodec

/** The columns `GET /api/games/{slug}/plays` will order by. Player is absent: filtering by it is a substring match on
  * `users.email`, but ordering by it would need a join this listing deliberately avoids (see `GameRepository`'s
  * `matchingPlays` doc comment) — the same split the admin user list's sign-in badge draws between "shown/filterable"
  * and "orderable".
  */
object GamePlaySort {
  val score: String     = "score"
  val wordCount: String = "wordCount"
  val startedAt: String = "startedAt"

  val all: List[String] = List(score, wordCount, startedAt)
}
