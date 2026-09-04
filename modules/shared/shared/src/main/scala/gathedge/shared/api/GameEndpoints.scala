package gathedge.shared.api

import gathedge.shared.domain.Tag
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
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure, withCodecError}
import ApiSchemas.given

object GameEndpoints {

  private val gameSlug = PathCodec.string("slug")
  private val playId   = PathCodec.long("playId")

  private val sourceLanguageQuery = HttpCodec.query[String]("sourceLanguage").optional
  private val targetLanguageQuery = HttpCodec.query[String]("targetLanguage").optional

  /** Comma-joined tag ids (`"1,2,3"`) rather than a repeated `?tagIds=1&tagIds=2` param — this codebase has no
    * precedent for a list-typed query codec (see `WordEndpoints.list`'s tuple-of-scalars workaround for the same gap),
    * so the route handler parses this by hand, the same spirit as that workaround.
    */
  private val tagIdsQuery = HttpCodec.query[String]("tagIds").optional

  private val swapDirectionQuery  = HttpCodec.query[Boolean]("swapDirection").optional
  private val wordPreferenceQuery = HttpCodec.query[String]("wordPreference").optional

  /** The games listing's "only my favorites" toggle — absent or `false` is the whole listing. */
  private val favoritesQuery = HttpCodec.query[Boolean]("favorites").optional

  /** The owner-facing plays listing's paging/sort/filter params — same shape as `AdminEndpoints`'s own, not shared
    * across files since neither hoists them today. `sort` names a column out of `dto.GamePlaySort`; `q` is a
    * case-insensitive substring of the player's address.
    */
  private val pageQuery     = HttpCodec.query[Int]("page").optional
  private val pageSizeQuery = HttpCodec.query[Int]("pageSize").optional
  private val sortQuery     = HttpCodec.query[String]("sort").optional
  private val dirQuery      = HttpCodec.query[String]("dir").optional
  private val searchQuery   = HttpCodec.query[String]("q").optional
  private val gameIdQuery   = HttpCodec.query[Long]("gameId").optional

  private val noContent = HttpCodec.status(Status.NoContent)

  val setup = {
    Endpoint(Method.GET / "api" / "games" / "setup")
      .query(sourceLanguageQuery)
      .query(targetLanguageQuery)
      .out[List[Tag]]
      .outFailure(failure.unauthorized)
  }

  /** The setup screen's word-list preview: exactly the eligible pool a game built from `tagIds` would draw from, before
    * any game exists — see `GameService.eligibleWords`. Session-only like [[setup]], for the same reason; an empty or
    * missing `tagIds` simply answers an empty list rather than a 400, since the setup form's own "no tags picked yet"
    * state is not an error.
    */
  val setupWords = {
    Endpoint(Method.GET / "api" / "games" / "setup" / "words")
      .query(sourceLanguageQuery)
      .query(targetLanguageQuery)
      .query(tagIdsQuery)
      .out[List[GameSetupWord]]
      .outFailure(failure.unauthorized)
  }

  /** Every account's games, one page at a time, most recently created first unless `sort` says otherwise — see
    * `GameService.allGames`. Paged/sorted/filtered the same way [[listPlays]] is; `sort` names a column out of
    * `dto.AllGameSort`, `q` is a case-insensitive substring of the game's name, and `favorites=true` keeps only games
    * the caller has favorited.
    */
  val allGames = {
    Endpoint(Method.GET / "api" / "games" / "all")
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .query(searchQuery)
      .query(favoritesQuery)
      .withCodecError
      .out[AllGamePage]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Marks `slug` as the caller's favorite — idempotent, so a repeated call is still a 204. `POST`/`DELETE` on the same
    * path toggle the heart on the games listing. See `GameService.favoriteGame`.
    */
  val favorite = {
    Endpoint(Method.POST / "api" / "games" / gameSlug / "favorite").withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** Clears the caller's favorite mark on `slug` — idempotent, a 204 whether or not it was marked. */
  val unfavorite = {
    Endpoint(Method.DELETE / "api" / "games" / gameSlug / "favorite").withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  val create = {
    Endpoint(Method.POST / "api" / "games")
      .in[CreateGameRequest]
      .withCodecError
      .out[GameCreated](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  val get = {
    Endpoint(Method.GET / "api" / "games" / gameSlug)
      .out[GameDetail]
      .outFailure(failure.notFound)
  }

  val rename = {
    Endpoint(Method.PATCH / "api" / "games" / gameSlug)
      .in[RenameGameRequest]
      .withCodecError
      .out[GameDetail]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Starts a fresh attempt at `slug` under the variant `body` describes — see [[StartPlayRequest]]. `badRequest`
    * covers both a body that fails validation (an out-of-range `wordLimit`, or one `>=` the resolved direction's
    * eligible pool) and `NoEligibleWords` (the resolved direction's pool is empty right now).
    */
  val startPlay = {
    Endpoint(Method.POST / "api" / "games" / gameSlug / "plays")
      .in[StartPlayRequest]
      .withCodecError
      .out[PlayStarted](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** The play-variant picker's preview: the resolved-direction eligible pool, in the order [[startPlay]] would sample
    * from for the same `swapDirection`/`wordPreference` — see `GameService.playSetupPreview`. Anonymous-capable, the
    * same reasoning [[get]] applies: a visitor opening a shared quiz link must be able to preview the picker before any
    * guest is minted. For a signed-in caller the `Unplayed`/`MostMistakes` ordering still uses their own play history
    * in this game; an anonymous caller has none, so both preferences degrade to the same order as `All`.
    */
  val playSetup = {
    Endpoint(Method.GET / "api" / "games" / gameSlug / "plays" / "setup")
      .query(swapDirectionQuery)
      .query(wordPreferenceQuery)
      .withCodecError
      .out[List[GameSetupWord]]
      .outErrors(failure.badRequest, failure.notFound)
  }

  /** The next word to answer in `playId`, or `{finished: true}` once none remain. `badRequest` covers a `playId` that
    * fails to parse as a `Long`; `forbidden` covers a `playId` that belongs to somebody else.
    */
  val nextPrompt = {
    Endpoint(Method.GET / "api" / "games" / "plays" / playId / "prompt").withCodecError
      .out[GamePrompt]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Scores one answer and answers with that one row, so the player is told at once whether it was right and what the
    * game would have accepted — see `GameService.submitAnswer`. The row is the same [[GameAnswerResult]] the finished
    * play's [[results]] table carries, built by the same code, so the two can never disagree. The running score stays
    * out of it: a player still learns their total only when the play ends.
    */
  val submitAnswer = {
    Endpoint(Method.POST / "api" / "games" / "plays" / playId / "answers")
      .in[SubmitAnswerRequest]
      .withCodecError
      .out[GameAnswerResult]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** The finished play's score and full answer history, for the results screen. */
  val results = {
    Endpoint(Method.GET / "api" / "games" / "plays" / playId / "results").withCodecError
      .out[GameResults]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Owner-only: one page of `slug`'s plays, most recent first unless `sort` says otherwise — see
    * `GameService.listPlays`.
    */
  val listPlays = {
    Endpoint(Method.GET / "api" / "games" / gameSlug / "plays")
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .query(searchQuery)
      .withCodecError
      .out[GamePlayPage]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Owner-only equivalent of [[results]]: one play's full answer history, addressed by game and play together so an
    * owner can never be handed a play id that belongs to somebody else's game. See `GameService.getPlayDetail`.
    */
  val playDetail = {
    Endpoint(Method.GET / "api" / "games" / gameSlug / "plays" / playId).withCodecError
      .out[GamePlayDetail]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** The caller's own play history across every game, most recently started first unless `sort` says otherwise — see
    * `GameService.myPlays`. Unlike [[listPlays]] this needs no ownership check: it is always the caller's own data. `q`
    * is a case-insensitive substring of the game's name, the cross-game counterpart of [[listPlays]]'s player filter.
    */
  val myPlays = {
    Endpoint(Method.GET / "api" / "games" / "plays" / "mine")
      .query(gameIdQuery)
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .query(searchQuery)
      .withCodecError
      .out[MyPlayPage]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(
    setup,
    setupWords,
    allGames,
    favorite,
    unfavorite,
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

  /** [[get]] and [[playSetup]] — a shared game link, and the play-variant picker's preview it leads to, must both be
    * viewable before any guest is minted, the same reasoning [[WordEndpoints.public]] applies to the dictionary reads.
    */
  val public: List[Endpoint[?, ?, ?, ?, ?]] = List(get, playSetup)
}
