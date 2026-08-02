package webapp1.backend.http

import webapp1.backend.service.{AdminFailure, AuthFailure, GroupFailure, TodoFailure}
import webapp1.shared.api.ApiFailure

/** One `Failure -> ApiFailure` mapping per service failure enum.
  *
  * These used to be `Failure -> Response` mappings that chose a status code as well as a body; the status now comes
  * from the endpoint description in `shared`, so all that is left here is picking the shape and the message. What has
  * not changed is that there is exactly *one* mapping per enum. They started out as `private` helpers duplicated inside
  * the individual route files, which let `InvitationRoutes` grow a second, divergent mapping over `GroupFailure`: it
  * matched only `InvitationInvalid` and `NotFound` and swept the remaining five cases into a generic 400, so the same
  * failure answered 403/409/400-with-field-errors under `/api/groups` but a bare 400 under `/api/invitations`. Keeping
  * one mapping per enum makes that class of drift impossible, and the compiler's exhaustivity check covers every
  * endpoint that can raise the failure.
  *
  * Each return type is the union of the cases that mapping can actually produce, not `ApiFailure`. That is what ties
  * these to the endpoint descriptions: a handler's `mapError(ApiFailures.todo)` only compiles if every status in this
  * union is one the endpoint declares, so adding a case here that an endpoint does not describe is a compile error at
  * the route rather than a failure to encode the response at request time.
  */
object ApiFailures {

  // Shared between the self-service signup path and admin user creation: same condition, same wire shape.
  private val emailAlreadyRegistered: ApiFailure.Conflict = {
    ApiFailure.Conflict("Email already registered")
  }

  private val invitationInvalid: ApiFailure.BadRequest = {
    ApiFailure.BadRequest("This invitation is invalid, expired, or already used")
  }

  def auth(
    failure: AuthFailure
  ): ApiFailure.BadRequest | ApiFailure.Unauthorized | ApiFailure.Conflict | ApiFailure.TooManyRequests = {
    failure match {
      case AuthFailure.InvalidCredentials =>
        ApiFailure.Unauthorized("Invalid email or password")
      case AuthFailure.EmailAlreadyRegistered =>
        emailAlreadyRegistered
      case AuthFailure.ValidationError(fieldErrors) =>
        ApiFailure.BadRequest("Validation failed", fieldErrors)
      case AuthFailure.RateLimited =>
        ApiFailure.TooManyRequests("Too many attempts. Try again later.")
      case AuthFailure.GoogleAuthFailed(reason) =>
        ApiFailure.BadRequest(s"Google sign-in failed: $reason")
    }
  }

  def todo(failure: TodoFailure): ApiFailure.BadRequest | ApiFailure.NotFound = {
    failure match {
      case TodoFailure.ValidationError(message) =>
        ApiFailure.BadRequest(message, Map("text" -> message))
      case TodoFailure.NotFound =>
        ApiFailure.NotFound("Todo item not found")
    }
  }

  def group(
    failure: GroupFailure
  ): ApiFailure.BadRequest | ApiFailure.Forbidden | ApiFailure.NotFound | ApiFailure.Conflict = {
    failure match {
      case GroupFailure.ValidationError(fieldErrors) =>
        ApiFailure.BadRequest("Validation failed", fieldErrors)
      case GroupFailure.NotFound =>
        ApiFailure.NotFound("Group not found")
      case GroupFailure.NotMember =>
        ApiFailure.Forbidden("You are not a member of this group")
      case GroupFailure.ReadOnlyMember =>
        ApiFailure.Forbidden("Your role in this group is read-only")
      case GroupFailure.AdminOnly =>
        ApiFailure.Forbidden("Only a group administrator can do this")
      case GroupFailure.LastAdmin =>
        ApiFailure.Conflict("A group must always have at least one administrator; promote another member first")
      case GroupFailure.InvitationInvalid =>
        invitationInvalid
    }
  }

  def admin(failure: AdminFailure): ApiFailure.BadRequest | ApiFailure.NotFound | ApiFailure.Conflict = {
    failure match {
      case AdminFailure.ValidationError(fieldErrors) =>
        ApiFailure.BadRequest("Validation failed", fieldErrors)
      case AdminFailure.DuplicateEmail =>
        emailAlreadyRegistered
      case AdminFailure.NotFound =>
        ApiFailure.NotFound("User not found")
      case AdminFailure.SelfDemote =>
        ApiFailure.BadRequest("You cannot remove your own administrator privileges")
      case AdminFailure.SelfDelete =>
        ApiFailure.BadRequest("You cannot delete your own account")
    }
  }
}
