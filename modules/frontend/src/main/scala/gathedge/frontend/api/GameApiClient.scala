package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.domain.{ArticleMode, GameMode, GameRef, Tag, WordLanguage, WordPreference}
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

import HttpClient.{query, segment}

/** The game catalog's calls, over [[HttpClient]] the same way [[WordApiClient]] is.
  *
  * [[setup]] and [[create]] require a session — see `GameSetupPage`'s guest detour, which sits in front of each.
  * [[get]] and [[playSetup]] do not — both are `optionalUser` reads a shared game link is opened through: the variant
  * picker's preview must be viewable before any guest is minted, same as the link itself. [[startPlay]] is the first
  * call in the play loop that needs a session, and the only one that mints a guest.
  */
object GameApiClient {

  /** The tags eligible for a quiz between `source` and `target`, own tags first — see `Tag.sorted`. */
  def setup(source: WordLanguage, target: WordLanguage): EventStream[Either[ApiError, List[Tag]]] = {
    HttpClient.get[List[Tag]](
      s"/api/games/setup${query("sourceLanguage" -> Some(WordLanguage.code(source)), "targetLanguage" -> Some(WordLanguage.code(target)))}"
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
      s"/api/games/setup/words${query(
          "sourceLanguage" -> Some(WordLanguage.code(source)),
          "targetLanguage" -> Some(WordLanguage.code(target)),
          "tagIds"         -> joined,
        )}"
    )
  }

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
    HttpClient.get[AllGamePage](
      s"/api/games/all${query(
          "page"      -> page,
          "pageSize"  -> pageSize,
          "sort"      -> sort,
          "dir"       -> dir,
          "q"         -> search,
          "favorites" -> favoritesOnly,
          "tag"       -> tagId,
          "lang1"     -> language1.map(WordLanguage.code),
          "lang2"     -> language2.map(WordLanguage.code),
        )}"
    )
  }

  /** The games whose wordlist set is exactly `tagIds` — the setup screen's "this quiz already exists" warning, not the
    * "carries this wordlist" filter [[allGames]]'s `tagId` applies. Needs no session either.
    */
  def gamesWithTags(tagIds: Set[Long]): EventStream[Either[ApiError, List[GameRef]]] = {
    val joined = Option.when(tagIds.nonEmpty)(tagIds.mkString(","))
    HttpClient.get[List[GameRef]](s"/api/games/same-tags${query("tagIds" -> joined)}")
  }

  /** Marks `slug` as the caller's favorite — idempotent, answers 204. */
  def favorite(slug: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, s"/api/games/${segment(slug)}/favorite")
  }

  /** Clears the caller's favorite mark on `slug` — idempotent, answers 204. */
  def unfavorite(slug: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/games/${segment(slug)}/favorite")
  }

  def create(
    source: WordLanguage,
    target: WordLanguage,
    tagIds: List[Long],
  ): EventStream[Either[ApiError, GameCreated]] = {
    HttpClient.post[GameCreated]("/api/games", Some(CreateGameRequest(source, target, tagIds).toJson))
  }

  /** A shared game link's detail — playable, and readable, by anybody. */
  def get(slug: String): EventStream[Either[ApiError, GameDetail]] = {
    HttpClient.get[GameDetail](s"/api/games/${segment(slug)}")
  }

  /** Owner-only — see `GameEndpoints.rename`'s doc comment. */
  def rename(slug: String, name: String): EventStream[Either[ApiError, GameDetail]] = {
    HttpClient.patch[GameDetail](s"/api/games/${segment(slug)}", Some(RenameGameRequest(name).toJson))
  }

  /** Owner-only — removes the game and every play, answer and favorite scoped to it. Answers 204. */
  def delete(slug: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/games/${segment(slug)}")
  }

  /** Starts a fresh attempt at `slug` under the given variant — see [[StartPlayRequest]]. */
  def startPlay(
    slug: String,
    swapDirection: Boolean = false,
    wordLimit: Option[Int] = None,
    articleMode: ArticleMode = ArticleMode.default,
    wordPreference: WordPreference = WordPreference.All,
    mode: GameMode = GameMode.Typing,
  ): EventStream[Either[ApiError, PlayStarted]] = {
    HttpClient.post[PlayStarted](
      s"/api/games/${segment(slug)}/plays",
      Some(StartPlayRequest(swapDirection, wordLimit, articleMode, wordPreference, mode).toJson),
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
      s"/api/games/${segment(slug)}/plays/setup${query("swapDirection" -> Some(swapDirection), "wordPreference" -> Some(WordPreference.code(wordPreference)))}"
    )
  }

  def nextPrompt(playId: Long): EventStream[Either[ApiError, GamePrompt]] = {
    HttpClient.get[GamePrompt](s"/api/games/plays/$playId/prompt")
  }

  /** Answers with the graded row, which is what the play loop shows the player before it moves on. */
  def submitAnswer(playId: Long, wordId: Long, answerText: String): EventStream[Either[ApiError, GameAnswerResult]] = {
    HttpClient.post[GameAnswerResult](
      s"/api/games/plays/$playId/answers",
      Some(SubmitAnswerRequest(wordId, answerText).toJson),
    )
  }

  /** The finished play's score, full answer history, and the variant it ran under. */
  def getResults(playId: Long): EventStream[Either[ApiError, GameResults]] = {
    HttpClient.get[GameResults](s"/api/games/plays/$playId/results")
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
    HttpClient.get[GamePlayPage](
      s"/api/games/${segment(slug)}/plays${query("page" -> page, "pageSize" -> pageSize, "sort" -> sort, "dir" -> dir, "q" -> search)}"
    )
  }

  /** Owner-only equivalent of [[getResults]]: one play's full answer history, for the result modal. */
  def getPlayDetail(slug: String, playId: Long): EventStream[Either[ApiError, GamePlayDetail]] = {
    HttpClient.get[GamePlayDetail](s"/api/games/${segment(slug)}/plays/$playId")
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
    HttpClient.get[MyPlayPage](
      s"/api/games/plays/mine${query(
          "gameId"   -> gameId,
          "page"     -> page,
          "pageSize" -> pageSize,
          "sort"     -> sort,
          "dir"      -> dir,
          "q"        -> search,
        )}"
    )
  }
}
