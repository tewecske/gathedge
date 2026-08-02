package webapp1.backend.http

import webapp1.shared.dto.ErrorResponse
import zio.http.*
import zio.json.*

/** What is left of hand-written JSON after the routes moved to the declarative `Endpoint` API: the error bodies built
  * outside any endpoint.
  *
  * The aspects in [[RouteSupport]] (session, admin check, CSRF) and its `handleFailures` wrapper run before or around a
  * handler, so they answer without going through an endpoint's codecs, as do the Google OAuth routes. Their bodies
  * still have to be byte-identical to what the endpoints produce for the same statuses — that is the `ErrorResponse` /
  * `api.ApiFailure` pairing, asserted in `ApiEndpointsSpec`.
  */
object JsonSupport {

  private def jsonResponse[A](status: Status, value: A)(using enc: JsonEncoder[A]): Response = {
    Response.json(enc.encodeJson(value, None)).status(status)
  }

  def errorResponse(status: Status, message: String, fieldErrors: Map[String, String] = Map.empty): Response = {
    jsonResponse(status, ErrorResponse(message, fieldErrors))
  }
}
