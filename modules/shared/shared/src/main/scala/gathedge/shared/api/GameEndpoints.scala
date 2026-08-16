package gathedge.shared.api

import gathedge.shared.domain.Tag
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

  private val noContent = HttpCodec.status(Status.NoContent)

  val setup = {
    Endpoint(Method.GET / "api" / "games" / "setup")
      .query(sourceLanguageQuery)
      .query(targetLanguageQuery)
      .out[List[Tag]]
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

  val all: List[Endpoint[?, ?, ?, ?, ?]] =
    List(setup, create, get, rename, startPlay, nextPrompt, submitAnswer, results)

  /** Just [[get]] — a shared game link must be viewable before any guest is minted, the same reasoning
    * [[WordEndpoints.public]] applies to the dictionary reads.
    */
  val public: List[Endpoint[?, ?, ?, ?, ?]] = List(get)
}
