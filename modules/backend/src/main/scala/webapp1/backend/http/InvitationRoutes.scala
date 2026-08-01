package webapp1.backend.http

import webapp1.backend.service.{AuthService, GroupService}
import webapp1.shared.domain.User
import zio.*
import zio.http.*

import JsonSupport.*

/** Invite-accept endpoints. Viewing an invitation is intentionally public (no session required) so the frontend can
  * show "you're invited to X" before the visitor signs in/up — the token itself is the secret. Accepting requires an
  * authenticated session whose email matches the invite, so only that route carries the `authenticated` aspect.
  */
object InvitationRoutes {

  private val getInvitationRoute = {
    Method.GET / "api" / "invitations" / string("token") ->
      handler { (token: String, _: Request) =>
        for {
          groupService <- ZIO.service[GroupService]
          info <- groupService.getInvitationInfo(token).mapError(FailureResponses.group)
        } yield jsonResponse(Status.Ok, info)
      }
  }

  private val acceptInvitationRoute = {
    Method.POST / "api" / "invitations" / string("token") / "accept" ->
      handler { (token: String, _: Request) =>
        for {
          user <- ZIO.service[User]
          groupService <- ZIO.service[GroupService]
          group <- groupService.acceptInvitation(user.id, user.email, token).mapError(FailureResponses.group)
        } yield jsonResponse(Status.Ok, group)
      }
  }

  /** The two endpoints differ in whether they need a session, so the aspect goes on a one-route `Routes` rather than on
    * the handler: attaching a context-providing aspect directly to a `handler` that also takes path parameters makes it
    * receive the bare `Request` where it expects the `(param, Request)` tuple.
    */
  val routes: Routes[AuthService & GroupService, Response] = {
    (Routes(getInvitationRoute) ++ (Routes(acceptInvitationRoute) @@ RouteSupport.authenticated)) @@ RouteSupport.csrf
  }
}
