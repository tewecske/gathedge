package webapp1.backend.http

import webapp1.backend.service.{AuthService, GroupFailure, GroupService}
import zio.*
import zio.http.*

import JsonSupport.*
import RouteSupport.{authenticatedUser, csrfCheck}

/** Invite-accept endpoints. Viewing an invitation is intentionally public (no
  * session required) so the frontend can show "you're invited to X" before the
  * visitor signs in/up — the token itself is the secret. Accepting requires an
  * authenticated session whose email matches the invite.
  */
object InvitationRoutes {

  private def groupFailureResponse(failure: GroupFailure): Response = failure match {
    case GroupFailure.InvitationInvalid => errorResponse(Status.BadRequest, "This invitation is invalid, expired, or already used")
    case GroupFailure.NotFound          => errorResponse(Status.NotFound, "Group not found")
    case _                              => errorResponse(Status.BadRequest, "Could not process this invitation")
  }

  private val getInvitationRoute =
    Method.GET / "api" / "invitations" / string("token") -> handler { (token: String, request: Request) =>
      (for {
        groupService <- ZIO.service[GroupService]
        info         <- groupService.getInvitationInfo(token).mapError(groupFailureResponse)
      } yield jsonResponse(Status.Ok, info)).merge
    }

  private val acceptInvitationRoute =
    Method.POST / "api" / "invitations" / string("token") / "accept" -> handler { (token: String, request: Request) =>
      (for {
        _            <- csrfCheck(request)
        user         <- authenticatedUser(request)
        groupService <- ZIO.service[GroupService]
        group        <- groupService.acceptInvitation(user.id, user.email, token).mapError(groupFailureResponse)
      } yield jsonResponse(Status.Ok, group)).merge
    }

  val routes: Routes[AuthService & GroupService, Nothing] =
    Routes(getInvitationRoute, acceptInvitationRoute)
}
