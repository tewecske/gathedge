package webapp1.backend.http

import webapp1.backend.service.{AuthService, GroupService}
import webapp1.shared.api.InvitationEndpoints
import webapp1.shared.domain.User
import zio.*
import zio.http.*

/** Invite-accept endpoints. Viewing an invitation is intentionally public (no session required) so the frontend can
  * show "you're invited to X" before the visitor signs in/up — the token itself is the secret. Accepting requires an
  * authenticated session whose email matches the invite, so only that route carries the `authenticated` aspect.
  */
object InvitationRoutes {

  private val getInvitationRoute = {
    InvitationEndpoints
      .getInvitation
      .implementHandler(
        handler { (token: String) =>
          ZIO.serviceWithZIO[GroupService](_.getInvitationInfo(token)).mapError(ApiFailures.group)
        }
      )
  }

  private val acceptInvitationRoute = {
    InvitationEndpoints
      .acceptInvitation
      .implementHandler(
        handler { (token: String) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            group <- groupService.acceptInvitation(user.id, user.email, token).mapError(ApiFailures.group)
          } yield group
        }
      )
  }

  /** The two endpoints differ in whether they need a session, so the aspect goes on a one-route `Routes` rather than on
    * the handler.
    */
  val routes: Routes[AuthService & GroupService, Response] = {
    (Routes(getInvitationRoute) ++ (Routes(acceptInvitationRoute) @@ RouteSupport.authenticated)) @@ RouteSupport.csrf
  }
}
