package webapp1.backend.http

import webapp1.shared.dto.ErrorResponse
import zio.*
import zio.http.*
import zio.json.*

object JsonSupport {

  def jsonResponse[A](status: Status, value: A)(using enc: JsonEncoder[A]): Response = {
    Response.json(enc.encodeJson(value, None)).status(status)
  }

  def errorResponse(status: Status, message: String, fieldErrors: Map[String, String] = Map.empty): Response = {
    jsonResponse(status, ErrorResponse(message, fieldErrors))
  }

  def readJson[A](request: Request)(using dec: JsonDecoder[A]): IO[Response, A] = {
    request
      .body
      .asString
      .mapError(err => errorResponse(Status.BadRequest, s"Could not read request body: ${err.getMessage}"))
      .flatMap { body =>
        ZIO.fromEither(dec.decodeJson(body)).mapError(err => errorResponse(Status.BadRequest, s"Malformed JSON: $err"))
      }
  }
}
