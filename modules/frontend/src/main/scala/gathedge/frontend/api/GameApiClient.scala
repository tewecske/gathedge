package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.GameEndpoints
import gathedge.shared.domain.{Tag, WordLanguage}
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

  /** The setup screen's word-list preview: exactly the eligible pool a game built from `tagIds` would draw from — see
    * `GameService.eligibleWords`. `tagIds` is joined as a comma-separated query string, since this codebase has no
    * list-typed query codec — see `GameEndpoints.tagIdsQuery`'s doc comment.
    */
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
    wordLimit: Option[Int] = None,
    randomizeEachPlay: Boolean = true,
    trackResults: Boolean = false,
  ): EventStream[Either[ApiError, GameCreated]] = {
    run(
      executor(
        GameEndpoints.create(
          CreateGameRequest(source, target, tagIds, wordLimit, randomizeEachPlay, trackResults)
        )
      )
    )
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

  /** Owner-only — see `GameEndpoints.reshuffle`'s doc comment. `GameInstancePage` only offers the control for a
    * `randomizeEachPlay = false` game the reader is shown as owning, the same gating [[rename]] applies.
    */
  def reshuffle(slug: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(GameEndpoints.reshuffle(slug)))
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

  /** Owner-only, and only for a `trackResults = true` game: one page of `slug`'s plays. Every paging parameter is
    * optional, the same shape [[AdminApiClient.listUsers]] follows.
    */
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
