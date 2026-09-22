package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.GamePaths
import gathedge.shared.domain.{GameMode, GameRef, Tag, WordLanguage, WordPreference}
import gathedge.shared.dto.{
  AllGamePage,
  CreateGameRequest,
  GameAnswerResult,
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
import zio.json._

import HttpClient.query

/** The game catalog's calls, over [[HttpClient]] the same way [[WordApiClient]] is.
  *
  * [[create]] requires a session — the wordlist pages' guest detour sits in front of it. [[get]] and [[playSetup]] do
  * not — both are `optionalUser` reads a shared game link is opened through: the variant picker's preview must be
  * viewable before any guest is minted, same as the link itself. [[startPlay]] is the first call in the play loop that
  * needs a session, and the only one that mints a guest.
  */
object GameApiClient {

  /** Every account's games, one page at a time, for the games table. `tagId` narrows to one wordlist; `language1`/
    * `language2` narrow to games whose language pair contains whichever of the two are given.
    */
  def allGames(
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
    favoritesOnly: Option[Boolean] = None,
    tagId: Option[Long] = None,
    language1: Option[WordLanguage] = None,
    language2: Option[WordLanguage] = None,
  ): EventStream[Either[ApiError, AllGamePage]] = {
    HttpClient.call[AllGamePage](
      GamePaths
        .allGames()
        .withQuery(
          query(
            "page"      -> page,
            "pageSize"  -> pageSize,
            "sort"      -> sort,
            "dir"       -> dir,
            "q"         -> search,
            "favorites" -> favoritesOnly,
            "tag"       -> tagId,
            "lang1"     -> language1.map(WordLanguage.code),
            "lang2"     -> language2.map(WordLanguage.code),
          )
        )
    )
  }

  /** The games whose wordlist set is exactly `tagIds` — the setup screen's "this quiz already exists" warning, not the
    * "carries this wordlist" filter [[allGames]]'s `tagId` applies. Needs no session either.
    */
  def gamesWithTags(tagIds: Set[Long]): EventStream[Either[ApiError, List[GameRef]]] = {
    val joined = Option.when(tagIds.nonEmpty)(tagIds.mkString(","))
    HttpClient.call[List[GameRef]](GamePaths.sameTagGames().withQuery(query("tagIds" -> joined)))
  }

  /** Marks `slug` as the caller's favorite — idempotent, answers 204. */
  def favorite(slug: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(GamePaths.favorite(slug))
  }

  /** Clears the caller's favorite mark on `slug` — idempotent, answers 204. */
  def unfavorite(slug: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(GamePaths.unfavorite(slug))
  }

  def create(
    source: WordLanguage,
    target: WordLanguage,
    tagIds: List[Long],
  ): EventStream[Either[ApiError, GameCreated]] = {
    HttpClient.call[GameCreated](GamePaths.create(), Some(CreateGameRequest(source, target, tagIds).toJson))
  }

  /** A shared game link's detail — playable, and readable, by anybody. */
  def get(slug: String): EventStream[Either[ApiError, GameDetail]] = {
    HttpClient.call[GameDetail](GamePaths.get(slug))
  }

  /** Owner-only — see `GameEndpoints.rename`'s doc comment. */
  def rename(slug: String, name: String): EventStream[Either[ApiError, GameDetail]] = {
    HttpClient.call[GameDetail](GamePaths.rename(slug), Some(RenameGameRequest(name).toJson))
  }

  /** Owner-only — removes the game and every play, answer and favorite scoped to it. Answers 204. */
  def delete(slug: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(GamePaths.delete(slug))
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
    HttpClient.call[PlayStarted](
      GamePaths.startPlay(slug),
      Some(StartPlayRequest(swapDirection, wordLimit, includeDefiniteArticles, wordPreference, mode).toJson),
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
    HttpClient.call[List[GameSetupWord]](
      GamePaths
        .playSetup(slug)
        .withQuery(
          query("swapDirection" -> Some(swapDirection), "wordPreference" -> Some(WordPreference.code(wordPreference)))
        )
    )
  }

  def nextPrompt(playId: Long): EventStream[Either[ApiError, GamePrompt]] = {
    HttpClient.call[GamePrompt](GamePaths.nextPrompt(playId))
  }

  /** Answers with the graded row, which is what the play loop shows the player before it moves on. */
  def submitAnswer(playId: Long, wordId: Long, answerText: String): EventStream[Either[ApiError, GameAnswerResult]] = {
    HttpClient
      .call[GameAnswerResult](GamePaths.submitAnswer(playId), Some(SubmitAnswerRequest(wordId, answerText).toJson))
  }

  /** The finished play's score, full answer history, and the variant it ran under. */
  def getResults(playId: Long): EventStream[Either[ApiError, GameResults]] = {
    HttpClient.call[GameResults](GamePaths.results(playId))
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
    HttpClient.call[GamePlayPage](
      GamePaths
        .listPlays(slug)
        .withQuery(query("page" -> page, "pageSize" -> pageSize, "sort" -> sort, "dir" -> dir, "q" -> search))
    )
  }

  /** Owner-only equivalent of [[getResults]]: one play's full answer history, for the result modal. */
  def getPlayDetail(slug: String, playId: Long): EventStream[Either[ApiError, GamePlayDetail]] = {
    HttpClient.call[GamePlayDetail](GamePaths.playDetail(slug, playId))
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
    HttpClient.call[MyPlayPage](
      GamePaths
        .myPlays()
        .withQuery(
          query(
            "gameId"   -> gameId,
            "page"     -> page,
            "pageSize" -> pageSize,
            "sort"     -> sort,
            "dir"      -> dir,
            "q"        -> search,
          )
        )
    )
  }
}
