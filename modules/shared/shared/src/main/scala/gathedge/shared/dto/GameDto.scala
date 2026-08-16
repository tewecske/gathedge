package gathedge.shared.dto

import gathedge.shared.domain.{AnswerOutcome, WordLanguage}
import zio.json.*

/** What `POST /api/games` needs: the language pair to draw words from, and which of the caller's eligible tags to build
  * the game out of.
  */
final case class CreateGameRequest(sourceLanguage: WordLanguage, targetLanguage: WordLanguage, tagIds: List[Long])
    derives JsonCodec

/** `POST /api/games`'s answer: just enough to navigate to the game and show its name — the caller already knows
  * everything else it just sent. The full [[GameDetail]] is a separate `GET`.
  */
final case class GameCreated(slug: String, name: String) derives JsonCodec

final case class RenameGameRequest(name: String) derives JsonCodec

/** A game as a caller may see it: no owner-only data, no id — `slug` is what a reader addresses it by. */
final case class GameDetail(
  slug: String,
  name: String,
  sourceLanguage: WordLanguage,
  targetLanguage: WordLanguage,
  tagNames: List[String],
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
