package gathedge.shared.api

import gathedge.shared.domain.Tag
import gathedge.shared.dto.{CreateGameRequest, GameCreated, GameDetail, RenameGameRequest}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure, withCodecError}
import ApiSchemas.given

object GameEndpoints {

  private val gameSlug = PathCodec.string("slug")

  private val sourceLanguageQuery = HttpCodec.query[String]("sourceLanguage").optional
  private val targetLanguageQuery = HttpCodec.query[String]("targetLanguage").optional

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

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(setup, create, get, rename)

  /** Just [[get]] — a shared game link must be viewable before any guest is minted, the same reasoning
    * [[WordEndpoints.public]] applies to the dictionary reads.
    */
  val public: List[Endpoint[?, ?, ?, ?, ?]] = List(get)
}
