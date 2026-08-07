package webapp1.backend.http

import webapp1.backend.service.{AdminFailure, AuthFailure, GroupFailure, TodoFailure}
import webapp1.shared.api.ApiFailure
import webapp1.shared.domain.OAuthProvider.display

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

  // One message for unknown, expired and already-redeemed alike, so a caller cannot tell them apart.
  private val verificationTokenInvalid: ApiFailure.BadRequest = {
    ApiFailure.BadRequest("This verification link is invalid, expired, or already used")
  }

  def auth(
    failure: AuthFailure
  ): ApiFailure.BadRequest | ApiFailure.Unauthorized | ApiFailure.Conflict | ApiFailure.TooManyRequests = {
    failure match {
      case AuthFailure.InvalidCredentials           =>
        ApiFailure.Unauthorized("Invalid email or password")
      case AuthFailure.EmailAlreadyRegistered       =>
        emailAlreadyRegistered
      case AuthFailure.ValidationError(fieldErrors) =>
        ApiFailure.BadRequest("Validation failed", fieldErrors)
      case AuthFailure.RateLimited                  =>
        ApiFailure.TooManyRequests("Too many attempts. Try again later.")
      case AuthFailure.OAuthFailed(reason)          =>
        ApiFailure.BadRequest(s"Sign-in failed: $reason")
      case AuthFailure.OAuthAccountExists(provider) =>
        ApiFailure.Conflict(
          s"An account with this email already exists. Sign in with your password, " +
            s"then link ${provider.display} from Settings."
        )
      case AuthFailure.OAuthAlreadyLinked           =>
        ApiFailure.Conflict("That account is already linked")
      case AuthFailure.LastCredential               =>
        ApiFailure.Conflict("Set a password first — this is the only way left to sign in to this account")
      case AuthFailure.EmailNotVerified             =>
        // Unreachable through this mapping: only `login` can raise it, and `login` uses
        // `authLogin` below to answer 403 instead. Mapped anyway because the match is exhaustive,
        // and to 401 because that is the only status every endpoint on this mapping declares.
        ApiFailure.Unauthorized("Invalid email or password")
      case AuthFailure.InvalidVerificationToken     =>
        verificationTokenInvalid
    }
  }

  /** [[auth]] plus the one failure that answers 403, kept separate so the 403 lands on `POST /api/auth/login` alone.
    *
    * Widening `auth`'s union instead would force every endpoint that maps through it — signup, unlink, set-password —
    * to describe a 403 none of them can raise, and 403 is otherwise deliberately undescribed across the API.
    */
  def authLogin(failure: AuthFailure): ApiFailure.BadRequest | ApiFailure.Unauthorized | ApiFailure.Forbidden |
    ApiFailure.Conflict | ApiFailure.TooManyRequests = {
    failure match {
      case AuthFailure.EmailNotVerified =>
        ApiFailure.Forbidden("Verify your email address before signing in")
      case other                        =>
        auth(other)
    }
  }

  /** Redeeming a verification link: the only failure [[webapp1.backend.service.AuthService.verifyEmail]] raises is
    * `InvalidVerificationToken`, and 400 is the only status that endpoint describes. The catch-all is a bug in the
    * service rather than a request the caller got wrong — answering it as a 400 keeps it inside the description, where
    * a wider union would instead fail at encode time.
    */
  def verifyEmail(failure: AuthFailure): ApiFailure.BadRequest = {
    failure match {
      case AuthFailure.ValidationError(fieldErrors) =>
        ApiFailure.BadRequest("Validation failed", fieldErrors)
      case _                                        =>
        verificationTokenInvalid
    }
  }

  /** Asking for a fresh verification link. Succeeds for unknown and already-verified addresses alike, so the rate
    * limiter's 429 is the only failure a caller can actually see; see [[verifyEmail]] on the catch-all.
    */
  def resendVerification(failure: AuthFailure): ApiFailure.BadRequest | ApiFailure.TooManyRequests = {
    failure match {
      case AuthFailure.RateLimited                  =>
        ApiFailure.TooManyRequests("Too many attempts. Try again later.")
      case AuthFailure.ValidationError(fieldErrors) =>
        ApiFailure.BadRequest("Validation failed", fieldErrors)
      case _                                        =>
        ApiFailure.BadRequest("Could not send a verification link")
    }
  }

  def todo(failure: TodoFailure): ApiFailure.BadRequest | ApiFailure.NotFound = {
    failure match {
      case TodoFailure.ValidationError(message) =>
        ApiFailure.BadRequest(message, Map("text" -> message))
      case TodoFailure.NotFound                 =>
        ApiFailure.NotFound("Todo item not found")
    }
  }

  def group(
    failure: GroupFailure
  ): ApiFailure.BadRequest | ApiFailure.Forbidden | ApiFailure.NotFound | ApiFailure.Conflict = {
    failure match {
      case GroupFailure.ValidationError(fieldErrors) =>
        ApiFailure.BadRequest("Validation failed", fieldErrors)
      case GroupFailure.NotFound                     =>
        ApiFailure.NotFound("Group not found")
      case GroupFailure.NotMember                    =>
        ApiFailure.Forbidden("You are not a member of this group")
      case GroupFailure.ReadOnlyMember               =>
        ApiFailure.Forbidden("Your role in this group is read-only")
      case GroupFailure.AdminOnly                    =>
        ApiFailure.Forbidden("Only a group administrator can do this")
      case GroupFailure.LastAdmin                    =>
        ApiFailure.Conflict("A group must always have at least one administrator; promote another member first")
      case GroupFailure.InvitationInvalid            =>
        invitationInvalid
    }
  }

  def admin(failure: AdminFailure): ApiFailure.BadRequest | ApiFailure.NotFound | ApiFailure.Conflict = {
    failure match {
      case AdminFailure.ValidationError(fieldErrors) =>
        ApiFailure.BadRequest("Validation failed", fieldErrors)
      case AdminFailure.DuplicateEmail               =>
        emailAlreadyRegistered
      case AdminFailure.NotFound                     =>
        ApiFailure.NotFound("User not found")
      case AdminFailure.SelfDemote                   =>
        ApiFailure.BadRequest("You cannot remove your own administrator privileges")
      case AdminFailure.SelfDelete                   =>
        ApiFailure.BadRequest("You cannot delete your own account")
      case AdminFailure.LastCredential               =>
        ApiFailure.Conflict("That is the account's only way to sign in; give it a password first")
    }
  }
}
