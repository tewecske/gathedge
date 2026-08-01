package webapp1.backend.http

import webapp1.backend.service.{AdminFailure, AuthFailure, GroupFailure, TodoFailure}
import zio.http.*

import JsonSupport.*

/** One `Failure -> Response` mapping per service failure enum.
  *
  * These used to be `private` helpers duplicated inside the individual route files, which let `InvitationRoutes` grow a
  * second, divergent mapping over `GroupFailure`: it matched only `InvitationInvalid` and `NotFound` and swept the
  * remaining five cases into a generic 400, so the same failure answered 403/409/400-with-field-errors under
  * `/api/groups` but a bare 400 under `/api/invitations`. Keeping exactly one mapping per enum makes that class of
  * drift impossible, and the compiler's exhaustivity check now covers every endpoint that can raise the failure.
  */
object FailureResponses {

  // Shared between the self-service signup path and admin user creation: same condition, same wire shape.
  private val emailAlreadyRegistered: Response = {
    errorResponse(Status.Conflict, "Email already registered", Map("email" -> "Email already registered"))
  }

  private val invitationInvalid: Response = {
    errorResponse(Status.BadRequest, "This invitation is invalid, expired, or already used")
  }

  def auth(failure: AuthFailure): Response = {
    failure match {
      case AuthFailure.InvalidCredentials =>
        errorResponse(Status.Unauthorized, "Invalid email or password")
      case AuthFailure.EmailAlreadyRegistered =>
        emailAlreadyRegistered
      case AuthFailure.ValidationError(fieldErrors) =>
        errorResponse(Status.BadRequest, "Validation failed", fieldErrors)
      case AuthFailure.RateLimited =>
        errorResponse(Status.TooManyRequests, "Too many attempts. Try again later.")
      case AuthFailure.GoogleAuthFailed(reason) =>
        errorResponse(Status.BadRequest, s"Google sign-in failed: $reason")
    }
  }

  def todo(failure: TodoFailure): Response = {
    failure match {
      case TodoFailure.ValidationError(message) =>
        errorResponse(Status.BadRequest, message, Map("text" -> message))
      case TodoFailure.NotFound =>
        errorResponse(Status.NotFound, "Todo item not found")
    }
  }

  def group(failure: GroupFailure): Response = {
    failure match {
      case GroupFailure.ValidationError(fieldErrors) =>
        errorResponse(Status.BadRequest, "Validation failed", fieldErrors)
      case GroupFailure.NotFound =>
        errorResponse(Status.NotFound, "Group not found")
      case GroupFailure.NotMember =>
        errorResponse(Status.Forbidden, "You are not a member of this group")
      case GroupFailure.ReadOnlyMember =>
        errorResponse(Status.Forbidden, "Your role in this group is read-only")
      case GroupFailure.AdminOnly =>
        errorResponse(Status.Forbidden, "Only a group administrator can do this")
      case GroupFailure.LastAdmin =>
        errorResponse(
          Status.Conflict,
          "A group must always have at least one administrator; promote another member first",
        )
      case GroupFailure.InvitationInvalid =>
        invitationInvalid
    }
  }

  def admin(failure: AdminFailure): Response = {
    failure match {
      case AdminFailure.ValidationError(fieldErrors) =>
        errorResponse(Status.BadRequest, "Validation failed", fieldErrors)
      case AdminFailure.DuplicateEmail =>
        emailAlreadyRegistered
      case AdminFailure.NotFound =>
        errorResponse(Status.NotFound, "User not found")
      case AdminFailure.SelfDemote =>
        errorResponse(Status.BadRequest, "You cannot remove your own administrator privileges")
      case AdminFailure.SelfDelete =>
        errorResponse(Status.BadRequest, "You cannot delete your own account")
    }
  }
}
