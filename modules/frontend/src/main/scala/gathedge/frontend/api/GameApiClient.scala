package gathedge.frontend.api

import com.raquo.laminar.api.L._
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
import zio.json._

import HttpClient.query

/** The game catalog's calls. The shared `GameEndpoints` description stays the backend's and the OpenAPI document's
  * source of truth, pinned by `ApiPathParitySpec`.
  *
  * [[setup]] and [[create]] require a session — see `GameSetupPage`'s guest detour, which sits in front of each.
  * [[get]] and [[playSetup]] do not — both are `optionalUser` reads a shared game link is opened through. [[startPlay]]
  * is the first call in the play loop that needs a session, and the only one that mints a guest.
  */
object GameApiClient {

  /** The tags eligible for a quiz between `source` and `target`, own tags first — see `Tag.sorted`. */
  def setup(source: WordLanguage, target: WordLanguage): EventStream[Either[ApiError, List[Tag]]] = {
    HttpClient.get[List[Tag]](
      "/api/games/setup" + query(
        "sourceLanguage" -> Some(WordLanguage.code(source)),
        "targetLanguage" -> Some(WordLanguage.code(target)),
      )
    )
  }

  /** The setup screen's word-list preview: exactly the eligible pool a game built from `tagIds` would draw from. */
  def setupWords(
    source: WordLanguage,
    target: WordLanguage,
    tagIds: Set[Long],
  ): EventStream[Either[ApiError, List[GameSetupWord]]] = {
    val joined = Option.when(tagIds.nonEmpty)(tagIds.mkString(","))
    HttpClient.get[List[GameSetupWord]](
      "/api/games/setup/words" + query(
        "sourceLanguage" -> Some(WordLanguage.code(source)),
        "targetLanguage" -> Some(WordLanguage.code(target)),
        "tagIds"         -> joined,
      )
    )
  }

  /** The signed-in caller's own games, for the "my games" table. */
  def myGames(): EventStream[Either[ApiError, List[MyGameSummary]]] = {
    HttpClient.get[List[MyGameSummary]]("/api/games/mine")
  }

  def create(
    source: WordLanguage,
    target: WordLanguage,
    tagIds: List[Long],
    trackResults: Boolean = false,
  ): EventStream[Either[ApiError, GameCreated]] = {
    HttpClient.post[GameCreated]("/api/games", Some(CreateGameRequest(source, target, tagIds, trackResults).toJson))
  }

  /** A shared game link's detail — playable, and readable, by anybody. */
  def get(slug: String): EventStream[Either[ApiError, GameDetail]] = {
    HttpClient.get[GameDetail](s"/api/games/$slug")
  }

  /** Owner-only — see `GameEndpoints.rename`'s doc comment. */
  def rename(slug: String, name: String): EventStream[Either[ApiError, GameDetail]] = {
    HttpClient.patch[GameDetail](s"/api/games/$slug", Some(RenameGameRequest(name).toJson))
  }

  /** Starts a fresh attempt at `slug` under the given variant — see [[StartPlayRequest]]. */
  def startPlay(
    slug: String,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    includeDefiniteArticles: Boolean = true,
    wordPreference: WordPreference = WordPreference.All,
  ): EventStream[Either[ApiError, PlayStarted]] = {
    HttpClient.post[PlayStarted](
      s"/api/games/$slug/plays",
      Some(StartPlayRequest(swapDirection, wordLimit, includeDefiniteArticles, wordPreference).toJson),
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
    HttpClient.get[List[GameSetupWord]](
      s"/api/games/$slug/plays/setup" + query(
        "swapDirection"  -> Some(swapDirection),
        "wordPreference" -> Some(WordPreference.code(wordPreference)),
      )
    )
  }

  def nextPrompt(playId: Long): EventStream[Either[ApiError, GamePrompt]] = {
    HttpClient.get[GamePrompt](s"/api/games/plays/$playId/prompt")
  }

  def submitAnswer(playId: Long, wordId: Long, answerText: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, s"/api/games/plays/$playId/answers", Some(SubmitAnswerRequest(wordId, answerText).toJson))
  }

  /** The finished play's score, full answer history, and the variant it ran under. */
  def getResults(playId: Long): EventStream[Either[ApiError, GameResults]] = {
    HttpClient.get[GameResults](s"/api/games/plays/$playId/results")
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
    HttpClient.get[GamePlayPage](
      s"/api/games/$slug/plays" + query(
        "page"     -> page,
        "pageSize" -> pageSize,
        "sort"     -> sort,
        "dir"      -> dir,
        "q"        -> search,
      )
    )
  }

  /** Owner-only equivalent of [[getResults]]: one play's full answer history, for the result modal. */
  def getPlayDetail(slug: String, playId: Long): EventStream[Either[ApiError, GamePlayDetail]] = {
    HttpClient.get[GamePlayDetail](s"/api/games/$slug/plays/$playId")
  }

  /** The caller's own play history across every game — never gated by `trackResults`, unlike [[listPlays]]. */
  def myPlays(
    gameId: Option[Long] = None,
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
  ): EventStream[Either[ApiError, MyPlayPage]] = {
    HttpClient.get[MyPlayPage](
      "/api/games/plays/mine" + query(
        "gameId"   -> gameId,
        "page"     -> page,
        "pageSize" -> pageSize,
        "sort"     -> sort,
        "dir"      -> dir,
      )
    )
  }
}
