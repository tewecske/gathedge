package webapp1.shared.api

import zio.schema.{DeriveSchema, Schema}

/** The failures the admin user-management endpoints can report.
  *
  * One case per HTTP status rather than one per domain cause, because the status is what the endpoint description binds
  * a codec to. Every case carries the same two fields as `dto.ErrorResponse` and encodes to the same JSON object, so
  * moving these endpoints to the declarative API did not change the wire format.
  *
  * `Unauthorized`, `Forbidden` and `InternalError` are never raised by a handler — they come from the `HandlerAspect`s
  * (session, admin check, CSRF) and from `RouteSupport.handleFailures`, which short-circuit before the handler runs.
  * They are described here anyway, because a status the description omits is not decodable by a client built from it:
  * the endpoint client fails such a response as a *defect* carrying "Expected status code ... but found ...", so an
  * expired session would surface as an unrecognisable crash instead of a 401 the caller can act on.
  */
sealed trait AdminApiError {
  def message: String
  def fieldErrors: Map[String, String]
}

object AdminApiError {

  final case class BadRequest(message: String, fieldErrors: Map[String, String] = Map.empty) extends AdminApiError

  final case class NotFound(message: String, fieldErrors: Map[String, String] = Map.empty) extends AdminApiError

  final case class Conflict(message: String, fieldErrors: Map[String, String] = Map.empty) extends AdminApiError

  /** No session, or an expired one. Raised by `RouteSupport.authenticated`/`adminOnly`. */
  final case class Unauthorized(message: String, fieldErrors: Map[String, String] = Map.empty) extends AdminApiError

  /** Signed in but not an administrator, or a mutating request without the CSRF header. */
  final case class Forbidden(message: String, fieldErrors: Map[String, String] = Map.empty) extends AdminApiError

  /** A defect that reached `RouteSupport.handleFailures`. */
  final case class InternalError(message: String, fieldErrors: Map[String, String] = Map.empty) extends AdminApiError

  given Schema[BadRequest] = DeriveSchema.gen[BadRequest]
  given Schema[NotFound] = DeriveSchema.gen[NotFound]
  given Schema[Conflict] = DeriveSchema.gen[Conflict]
  given Schema[Unauthorized] = DeriveSchema.gen[Unauthorized]
  given Schema[Forbidden] = DeriveSchema.gen[Forbidden]
  given Schema[InternalError] = DeriveSchema.gen[InternalError]
}
