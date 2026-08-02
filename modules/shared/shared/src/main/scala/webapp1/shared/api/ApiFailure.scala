package webapp1.shared.api

import zio.schema.{DeriveSchema, Schema}

/** The failures every described endpoint can report.
  *
  * One case per HTTP status rather than one per domain cause, because the status is what an endpoint description binds
  * a codec to. Every case carries the same two fields as `dto.ErrorResponse` and encodes to the same JSON object, so
  * moving the routes to the declarative API did not change the wire format — the aspects in `RouteSupport` still build
  * their `ErrorResponse` bodies by hand and the two shapes have to stay identical (`ApiEndpointsSpec` pins that).
  *
  * `Unauthorized`, `Forbidden`, `TooManyRequests` and `InternalError` are mostly not raised by any handler — they come
  * from the `HandlerAspect`s (session, admin check, CSRF), from the rate limiter, and from
  * `RouteSupport.handleFailures`, all of which short-circuit before or around the handler. They are declared anyway,
  * because a status a description omits is not decodable by a client built from it: the endpoint client fails such a
  * response as a *defect* carrying "Expected status code ... but found ...", so an expired session would surface as an
  * unrecognisable crash instead of a 401 the caller can act on.
  */
sealed trait ApiFailure {
  def message: String
  def fieldErrors: Map[String, String]
}

object ApiFailure {

  /** Validation, and every domain rule the caller could have satisfied by sending something else. */
  final case class BadRequest(message: String, fieldErrors: Map[String, String] = Map.empty) extends ApiFailure

  /** No session, an expired one, or credentials that didn't match. */
  final case class Unauthorized(message: String, fieldErrors: Map[String, String] = Map.empty) extends ApiFailure

  /** Signed in but not allowed: not an administrator, not a member of the group, read-only in it — or a mutating
    * request without the CSRF header.
    */
  final case class Forbidden(message: String, fieldErrors: Map[String, String] = Map.empty) extends ApiFailure

  final case class NotFound(message: String, fieldErrors: Map[String, String] = Map.empty) extends ApiFailure

  /** The request conflicts with the current state: a taken email address, a group's last administrator. */
  final case class Conflict(message: String, fieldErrors: Map[String, String] = Map.empty) extends ApiFailure

  /** Raised by the login/signup rate limiter. */
  final case class TooManyRequests(message: String, fieldErrors: Map[String, String] = Map.empty) extends ApiFailure

  /** A defect that reached `RouteSupport.handleFailures`. */
  final case class InternalError(message: String, fieldErrors: Map[String, String] = Map.empty) extends ApiFailure

  given Schema[BadRequest] = DeriveSchema.gen[BadRequest]
  given Schema[Unauthorized] = DeriveSchema.gen[Unauthorized]
  given Schema[Forbidden] = DeriveSchema.gen[Forbidden]
  given Schema[NotFound] = DeriveSchema.gen[NotFound]
  given Schema[Conflict] = DeriveSchema.gen[Conflict]
  given Schema[TooManyRequests] = DeriveSchema.gen[TooManyRequests]
  given Schema[InternalError] = DeriveSchema.gen[InternalError]
}
