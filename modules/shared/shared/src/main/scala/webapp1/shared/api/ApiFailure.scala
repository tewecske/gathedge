package webapp1.shared.api

import zio.schema.{DeriveSchema, Schema}

/** The failures a described endpoint can report, one case per HTTP status.
  *
  * Per status rather than per domain cause, because the status is what an endpoint description binds a codec to. No
  * endpoint declares all of them: each one lists exactly the cases it can answer with, and its error channel is the
  * union of those — see `ApiEndpoint.failingWith` for how that list is chosen and what it buys.
  *
  * Only [[BadRequest]] carries `fieldErrors`. It is the only failure a form can attribute to individual inputs, and it
  * is the only one any `ApiFailures` mapping ever fills in; the other six were carrying an always-empty `Map` purely
  * because the sealed trait demanded it.
  *
  * Every case encodes to the same JSON object as `dto.ErrorResponse` does for the same status, because the aspects in
  * `RouteSupport` build their bodies from that DTO by hand and never pass through these codecs. `ApiEndpointsSpec` pins
  * the agreement on real bytes.
  */
sealed trait ApiFailure {
  def message: String
}

object ApiFailure {

  /** Validation, and every domain rule the caller could have satisfied by sending something else.
    *
    * `fieldErrors` maps an input name to its message, so a form can show the failure next to the field that caused it
    * rather than only at the top. Empty when the failure is not attributable to one input.
    */
  final case class BadRequest(message: String, fieldErrors: Map[String, String] = Map.empty) extends ApiFailure

  /** No session, an expired one, or credentials that didn't match. */
  final case class Unauthorized(message: String) extends ApiFailure

  /** Signed in but not allowed: not an administrator, not a member of the group, read-only in it — or a mutating
    * request without the CSRF header.
    */
  final case class Forbidden(message: String) extends ApiFailure

  /** A resource the request named does not exist — a todo id, a group, a user. Not the same thing as a request whose
    * path matches no route at all: that one never reaches an endpoint, is answered by `RouteSupport.handleFailures`'s
    * replacement for `Routes.notFound`, and is deliberately absent from the OpenAPI document because there is no
    * operation to document it on.
    */
  final case class NotFound(message: String) extends ApiFailure

  /** The request conflicts with the current state: a taken email address, a group's last administrator. */
  final case class Conflict(message: String) extends ApiFailure

  /** Raised by the login/signup rate limiter, and declared only by those two endpoints. */
  final case class TooManyRequests(message: String) extends ApiFailure

  /** A defect that reached `RouteSupport.handleFailures`. Declared by every endpoint, since any of them can have one.
    */
  final case class InternalError(message: String) extends ApiFailure

  given Schema[BadRequest] = DeriveSchema.gen[BadRequest]
  given Schema[Unauthorized] = DeriveSchema.gen[Unauthorized]
  given Schema[Forbidden] = DeriveSchema.gen[Forbidden]
  given Schema[NotFound] = DeriveSchema.gen[NotFound]
  given Schema[Conflict] = DeriveSchema.gen[Conflict]
  given Schema[TooManyRequests] = DeriveSchema.gen[TooManyRequests]
  given Schema[InternalError] = DeriveSchema.gen[InternalError]
}
