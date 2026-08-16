package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.GameEndpoints
import gathedge.shared.domain.{Tag, WordLanguage}
import gathedge.shared.dto.{
  CreateGameRequest,
  GameCreated,
  GameDetail,
  GamePrompt,
  GameResults,
  PlayStarted,
  RenameGameRequest,
  SubmitAnswerRequest,
}

import EndpointClient.{executor, run}

/** The game catalog's calls, generated from `GameEndpoints` the same way [[WordApiClient]] is from `WordEndpoints`.
  *
  * [[setup]] and [[create]] require a session — see `GameSetupPage`'s guest detour, which sits in front of each.
  * [[get]] does not — it is the `optionalUser` read a shared game link is opened through, the same reasoning
  * `WordApiClient.get` applies to the dictionary. [[startPlay]] is the first call in the play loop that needs a
  * session, so `GameInstancePage` puts the same guest detour in front of it, and it alone; [[nextPrompt]] and
  * [[submitAnswer]] run only once a play exists, by which point the session is already there.
  */
object GameApiClient {

  /** The tags eligible for a quiz between `source` and `target`, own tags first — see `Tag.sorted`. */
  def setup(source: WordLanguage, target: WordLanguage): EventStream[Either[ApiError, List[Tag]]] = {
    run(executor(GameEndpoints.setup(Some(WordLanguage.code(source)), Some(WordLanguage.code(target)))))
  }

  def create(
    source: WordLanguage,
    target: WordLanguage,
    tagIds: List[Long],
  ): EventStream[Either[ApiError, GameCreated]] = {
    run(executor(GameEndpoints.create(CreateGameRequest(source, target, tagIds))))
  }

  /** A shared game link's detail — playable, and readable, by anybody. */
  def get(slug: String): EventStream[Either[ApiError, GameDetail]] = {
    run(executor(GameEndpoints.get(slug)))
  }

  /** Owner-only — see `GameEndpoints.rename`'s doc comment. `GameInstancePage` only offers the control behind
    * `GameOwnership.isOwned`, but the 403 this can still answer (a different account, or a stale local hint) is what
    * actually enforces it.
    */
  def rename(slug: String, name: String): EventStream[Either[ApiError, GameDetail]] = {
    run(executor(GameEndpoints.rename(slug, RenameGameRequest(name))))
  }

  def startPlay(slug: String): EventStream[Either[ApiError, PlayStarted]] = {
    run(executor(GameEndpoints.startPlay(slug)))
  }

  def nextPrompt(playId: Long): EventStream[Either[ApiError, GamePrompt]] = {
    run(executor(GameEndpoints.nextPrompt(playId)))
  }

  def submitAnswer(playId: Long, wordId: Long, answerText: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(GameEndpoints.submitAnswer(playId, SubmitAnswerRequest(wordId, answerText))))
  }

  /** The finished play's score and full answer history, for the results screen. */
  def getResults(playId: Long): EventStream[Either[ApiError, GameResults]] = {
    run(executor(GameEndpoints.results(playId)))
  }
}
