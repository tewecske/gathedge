package gathedge.shared.dto

import gathedge.shared.domain.{AnswerOutcome, WordLanguage, WordPreference}
import zio.json.*

/** What `POST /api/games` needs: the language pair and tags a base game is built from. Nothing here ever changes after
  * creation — word count, direction, article display and word preference are all play-time choices now, carried by
  * [[StartPlayRequest]] instead. See the "game variants redesign" design doc.
  */
final case class CreateGameRequest(
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagIds: List[Long],
) derives JsonCodec

/** `POST /api/games`'s answer: just enough to navigate to the game and show its name. */
final case class GameCreated(slug: String, name: String) derives JsonCodec

final case class RenameGameRequest(name: String) derives JsonCodec

/** One row of `GET /api/games/setup/words`'s answer: the setup screen's preview of exactly the pool a game built from
  * the requested tags and language pair would draw from — `text` already carries a gendered source word's article, the
  * same [[gathedge.shared.domain.Word.displayText]] every prompt/result elsewhere in the game uses. Deduped to one row
  * per source word. `translations` is the word's marked accepted translation(s) — empty where nobody has populated it
  * (`GET /api/games/{slug}/plays/setup`'s play-time preview reuses this DTO unmodified and never fills it in).
  */
final case class GameSetupWord(wordId: Long, text: String, translations: List[String] = Nil) derives JsonCodec

/** A game as a caller may see it: no owner-only data, no id — `slug` is what a reader addresses it by. */
final case class GameDetail(
  slug: String,
  name: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagNames: List[String],
) derives JsonCodec

/** `POST /api/games/{slug}/plays`'s request body: the play-time variant a player picks fresh every time. See the design
  * doc's "priority sampling, not a hard filter" section for what [[wordPreference]] actually does.
  *
  * `swapDirection`: `true` plays the game's `targetLanguage` -> `sourceLanguage` instead of its stored direction.
  * `wordLimit`: `None` = every eligible word in the resolved direction (the default); `Some(n)` = sample `n` (or the
  * whole pool, if smaller). `includeDefiniteArticles`: `true` (the default) keeps a German noun's "der"/"die"/"das" in
  * the prompt, the accepted answer, and the results text. `wordPreference`: `All` (the default) samples uniformly; the
  * other two cases only change *which* words a narrowed sample favors, never the total count.
  */
final case class StartPlayRequest(
  swapDirection: Boolean = false,
  wordLimit: Option[Int] = None,
  includeDefiniteArticles: Boolean = true,
  wordPreference: WordPreference = WordPreference.All,
) derives JsonCodec

/** The variant settings one specific play actually ran under — a snapshot, not a live reference to the (now immutable)
  * base game, since a play may have swapped direction or picked a narrower/differently-preferenced sample than another
  * play of the same game. Embedded in every play-facing DTO: [[GameResults]], `GamePlaySummary`, `GamePlayDetail`,
  * `MyPlaySummary`.
  */
final case class GameVariantDto(
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  wordLimit: Option[Int],
  includeDefiniteArticles: Boolean,
  wordPreference: WordPreference,
) derives JsonCodec

/** `POST /api/games/{slug}/plays`'s answer: enough for the play loop to start — the id every later play call addresses,
  * and the two numbers a progress bar needs.
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

/** `GET /api/games/plays/{playId}/results`'s answer: the finished play's score, full answer history, and the variant it
  * was played under.
  */
final case class GameResults(
  score: Int,
  maxScore: Int,
  wordCount: Int,
  answers: List[GameAnswerResult],
  variant: GameVariantDto,
) derives JsonCodec

/** One row of `GET /api/games/all` — every account's games, most recently created first. `playCount` is `0` for a game
  * nobody has played yet, never absent.
  */
final case class AllGameSummary(
  slug: String,
  name: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagNames: List[String],
  playCount: Long,
  createdAt: Long,
) derives JsonCodec

/** One page of every account's games. `total` counts what matches the name filter, the same rule [[GamePlayPage]]
  * follows for its own listing.
  */
final case class AllGamePage(items: List[AllGameSummary], total: Long) derives JsonCodec

/** The columns `GET /api/games/all` will order by. Tags, the language pair and the play count are absent: the play
  * count is an aggregate this listing does not join, and the rest are labels, the same split [[GamePlaySort]] draws.
  */
object AllGameSort {
  val name: String      = "name"
  val createdAt: String = "createdAt"

  val all: List[String] = List(name, createdAt)
}

/** One row of `GET /api/games/{slug}/plays` — a game's owner-facing plays listing. `playerEmail` is `None` for a guest
  * who never gave one; `playerIsGuest` lets the table badge that instead of showing a blank cell. `variant` is the
  * settings this particular play actually ran under.
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

/** One page of a game's plays. `total` counts what matches the player filter, the same rule [[UserPage]] follows. */
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

/** The columns `GET /api/games/{slug}/plays` will order by. Player is absent: filtering by it is a substring match on
  * `users.email`, but ordering by it would need a join this listing deliberately avoids.
  */
object GamePlaySort {
  val score: String     = "score"
  val wordCount: String = "wordCount"
  val startedAt: String = "startedAt"

  val all: List[String] = List(score, wordCount, startedAt)
}
