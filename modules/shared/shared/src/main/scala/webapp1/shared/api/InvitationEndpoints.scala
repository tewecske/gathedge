package webapp1.shared.api

import webapp1.shared.domain.{Group, InvitationInfo}
import zio.http.Method
import zio.http.codec.PathCodec
import zio.http.endpoint.Endpoint

import ApiEndpoint.withErrors
import ApiSchemas.given

/** Viewing and accepting a group invitation.
  *
  * Viewing is public by design — the visitor follows an emailed link before they have an account, and the token is the
  * secret. Accepting needs a session whose email matches the invitation. Neither fact is expressible in a description;
  * it is the `authenticated` aspect being attached to only one of the two `Routes` values in `InvitationRoutes`.
  */
object InvitationEndpoints {

  private val token = PathCodec.string("token")

  val getInvitation = {
    withErrors(Endpoint(Method.GET / "api" / "invitations" / token).out[InvitationInfo])
  }

  /** Takes no body: the token in the path is the whole request, and the joined group comes back. */
  val acceptInvitation = {
    withErrors(Endpoint(Method.POST / "api" / "invitations" / token / "accept").out[Group])
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(getInvitation, acceptInvitation)
}
