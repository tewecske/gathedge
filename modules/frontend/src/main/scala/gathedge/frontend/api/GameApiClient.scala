package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.GameEndpoints
import gathedge.shared.domain.{GameMode, Tag, WordLanguage, WordPreference}
import gathedge.shared.dto.{
  AllGamePage,
  CreateGameRequest,
  GameCreated,
  GameDetail,
  GamePlayDetail,
  GamePlayPage,
  GamePrompt,
  GameResults,
  GameSetupWord,
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
  * [[get]] and [[playSetup]] do not — both are `optionalUser` reads a shared game link is opened through: the variant
  * picker's preview must be viewable before any guest is minted, same as the link itself. [[startPlay]] is the first
  * call in the play loop that needs a session, and the only one that mints a guest.
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

  /** Every account's games, one page at a time, for the games table. */
  def allGames(
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
    favoritesOnly: Option[Boolean] = None,
  ): EventStream[Either[ApiError, AllGamePage]] = {
    run(executor(GameEndpoints.allGames(page, pageSize, sort, dir, search, favoritesOnly)))
  }

  /** Marks `slug` as the caller's favorite — idempotent, answers 204. */
  def favorite(slug: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(GameEndpoints.favorite(slug)))
  }

  /** Clears the caller's favorite mark on `slug` — idempotent, answers 204. */
  def unfavorite(slug: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(GameEndpoints.unfavorite(slug)))
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
    mode: GameMode = GameMode.Typing,
  ): EventStream[Either[ApiError, PlayStarted]] = {
    run(
      executor(
        GameEndpoints.startPlay(
          slug,
          StartPlayRequest(swapDirection, wordLimit, includeDefiniteArticles, wordPreference, mode),
        )
      )
    )
  }

  /** The play-variant picker's preview: the resolved-direction eligible pool, in the order [[startPlay]] would sample
    * from for the same `swapDirection`/`wordPreference`.
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

  /** Owner-only: one page of `slug`'s plays. */
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

  /** The caller's own play history across every game — always the caller's own data, unlike [[listPlays]]. */
  def myPlays(
    gameId: Option[Long] = None,
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
  ): EventStream[Either[ApiError, MyPlayPage]] = {
    run(executor(GameEndpoints.myPlays(gameId, page, pageSize, sort, dir, search)))
  }
}
