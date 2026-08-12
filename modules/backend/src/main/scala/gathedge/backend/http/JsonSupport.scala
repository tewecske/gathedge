package gathedge.backend.http

import gathedge.shared.dto.ErrorResponse
import gathedge.shared.i18n.MessageRef
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

  /** `error` is the catalog key the SPA words; `message` is the English fallback that goes with it, exactly as on every
    * `api.ApiFailure` case. Both are required rather than one being derived from the other, because these bodies bypass
    * the endpoint codecs and there is nothing else to keep the two halves together.
    */
  def errorResponse(
    status: Status,
    error: MessageRef,
    message: String,
    fieldErrors: Map[String, MessageRef] = Map.empty,
  ): Response = {
    jsonResponse(status, ErrorResponse(error, message, fieldErrors))
  }
}
