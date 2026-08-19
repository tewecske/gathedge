package gathedge.shared.api

import gathedge.shared.domain.Tag
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

  /** The caller's own games, most recently created first — see `GameService.myGames`. Takes no input, so its only
    * failure is the aspect's 401, the same shape as [[setup]].
    */
  val mine = {
    Endpoint(Method.GET / "api" / "games" / "mine")
      .out[List[MyGameSummary]]
      .outFailure(failure.unauthorized)
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

  /** Owner-only: redraws a `randomizeEachPlay = false` game's fixed word pool. `conflict` covers a game with nothing
    * fixed to reshuffle (`randomizeEachPlay = true`, or no word limit) — see `GameService.reshuffle`.
    */
  val reshuffle = {
    Endpoint(Method.POST / "api" / "games" / gameSlug / "reshuffle")
      .outCodec(noContent)
      .outErrors(failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** Starts a fresh attempt at `slug`. `NoTagsSelected`/`TagNotEligible`/`ValidationError` are unreachable here — they
    * belong to [[create]] — but the pool can still turn out empty if a tag's pairs were removed after the game was
    * created, hence `badRequest` alongside `notFound`.
    */
  val startPlay = {
    Endpoint(Method.POST / "api" / "games" / gameSlug / "plays")
      .out[PlayStarted](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** The next word to answer in `playId`, or `{finished: true}` once none remain. `badRequest` covers a `playId` that
    * fails to parse as a `Long`; `forbidden` covers a `playId` that belongs to somebody else.
    */
  val nextPrompt = {
    Endpoint(Method.GET / "api" / "games" / "plays" / playId / "prompt").withCodecError
      .out[GamePrompt]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Scores one answer. Answers with a bare 204 rather than the score — see `GameService.submitAnswer`'s doc comment on
    * why a player is never shown correctness mid-game.
    */
  val submitAnswer = {
    Endpoint(Method.POST / "api" / "games" / "plays" / playId / "answers")
      .in[SubmitAnswerRequest]
      .withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** The finished play's score and full answer history, for the results screen. */
  val results = {
    Endpoint(Method.GET / "api" / "games" / "plays" / playId / "results").withCodecError
      .out[GameResults]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Owner-only, and only for a `trackResults = true` game: one page of `slug`'s plays, most recent first unless `sort`
    * says otherwise — see `GameService.listPlays`. `conflict` covers a game that does not track results.
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
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** Owner-only equivalent of [[results]]: one play's full answer history, addressed by game and play together so an
    * owner can never be handed a play id that belongs to somebody else's game. See `GameService.getPlayDetail`.
    */
  val playDetail = {
    Endpoint(Method.GET / "api" / "games" / gameSlug / "plays" / playId).withCodecError
      .out[GamePlayDetail]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** The caller's own play history across every game, most recently started first unless `sort` says otherwise — see
    * `GameService.myPlays`. Unlike [[listPlays]] this is never gated by `trackResults`: it is always the caller's own
    * data, the same reasoning [[results]] is never gated either.
    */
  val myPlays = {
    Endpoint(Method.GET / "api" / "games" / "plays" / "mine")
      .query(gameIdQuery)
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .withCodecError
      .out[MyPlayPage]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(
    setup,
    setupWords,
    mine,
    myPlays,
    create,
    get,
    rename,
    reshuffle,
    startPlay,
    nextPrompt,
    submitAnswer,
    results,
    listPlays,
    playDetail,
  )

  /** Just [[get]] — a shared game link must be viewable before any guest is minted, the same reasoning
    * [[WordEndpoints.public]] applies to the dictionary reads.
    */
  val public: List[Endpoint[?, ?, ?, ?, ?]] = List(get)
}
