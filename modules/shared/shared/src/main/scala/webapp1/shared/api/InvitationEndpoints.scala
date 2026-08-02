package webapp1.shared.api

import webapp1.shared.domain.{Group, InvitationInfo}
import zio.http.Method
import zio.http.codec.PathCodec
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failingWith, failure}
import ApiSchemas.given

/** Viewing and accepting a group invitation.
  *
  * Viewing is public by design — the visitor follows an emailed link before they have an account, and the token is the
  * secret. Accepting needs a session whose email matches the invitation. Neither fact is expressible in a description;
  * it is the `authenticated` aspect being attached to only one of the two `Routes` values in `InvitationRoutes`. What
  * *is* visible here is the consequence: [[getInvitation]] is the one endpoint in the API that does not declare 401.
  */
object InvitationEndpoints {

  private val token = PathCodec.string("token")

  /** Public, and a GET, so neither the session aspect's 401 nor the CSRF aspect's 403 applies. The 403/404/409 come
    * from `ApiFailures.group`, which maps the whole `GroupFailure` enum — see the note on [[GroupEndpoints]].
    */
  val getInvitation = {
    Endpoint(Method.GET / "api" / "invitations" / token)
      .out[InvitationInfo]
      .failingWith(failure.badRequest, failure.forbidden, failure.notFound, failure.conflict, failure.internalError)
  }

  /** Takes no body: the token in the path is the whole request, and the joined group comes back. */
  val acceptInvitation = {
    Endpoint(Method.POST / "api" / "invitations" / token / "accept")
      .out[Group]
      .failingWith(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(getInvitation, acceptInvitation)
}
