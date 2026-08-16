package gathedge.backend.http

import gathedge.backend.service.{
  AdminFailure,
  AuthFailure,
  GameFailure,
  GuestAccountFailure,
  GuestClaimFailure,
  GuestCodeFailure,
  GuestMintFailure,
  WordFailure,
}
import gathedge.shared.api.ApiFailure
import gathedge.shared.domain.OAuthProvider.display
import gathedge.shared.i18n.{MessageKeys, MessageRef}

/** One `Failure -> ApiFailure` mapping per service failure enum.
  *
  * These used to be `Failure -> Response` mappings that chose a status code as well as a body; the status now comes
  * from the endpoint description in `shared`, so all that is left here is picking the shape and the message. What has
  * not changed is that there is exactly *one* mapping per enum. They started out as `private` helpers duplicated inside
  * the individual route files, which is how two route files implementing endpoints over the same failure enum came to
  * answer the same failure with different statuses and different bodies. Keeping one mapping per enum makes that class
  * of drift impossible, and the compiler's exhaustivity check covers every endpoint that can raise the failure.
  *
  * Each return type is the union of the cases that mapping can actually produce, not `ApiFailure`. That is what ties
  * these to the endpoint descriptions: a handler's `mapError(ApiFailures.admin)` only compiles if every status in this
  * union is one the endpoint declares, so adding a case here that an endpoint does not describe is a compile error at
  * the route rather than a failure to encode the response at request time.
  *
  * '''This is the one place a message *key* is chosen, and no signature here takes a locale.''' That is the whole
  * bargain of putting codes on the wire: the server stays language-free and the browser does the wording, so
  * translating an error never means threading a `Locale` through every route. The English text alongside each key is a
  * fallback for callers with no catalog (a `curl`, the OpenAPI examples) and is never what the SPA renders — the two
  * are kept in step by `MessagesSpec`, which fails if a key here is missing from either catalog.
  */
object ApiFailures {

  // Shared between the self-service signup path and admin user creation: same condition, same wire shape.
  private val emailAlreadyRegistered: ApiFailure.Conflict = {
    ApiFailure.Conflict(MessageRef(MessageKeys.emailAlreadyRegistered), "Email already registered")
  }

  // One message for unknown, expired and already-redeemed alike, so a caller cannot tell them apart.
  private val verificationTokenInvalid: ApiFailure.BadRequest = {
    ApiFailure.BadRequest(
      MessageRef(MessageKeys.verificationTokenInvalid),
      "This verification link is invalid, expired, or already used",
    )
  }

  // Same shape as `verificationTokenInvalid`, for the reset-password link.
  private val passwordResetTokenInvalid: ApiFailure.BadRequest = {
    ApiFailure.BadRequest(
      MessageRef(MessageKeys.passwordResetTokenInvalid),
      "This password reset link is invalid, expired, or already used",
    )
  }

  private val rateLimited: ApiFailure.TooManyRequests = {
    ApiFailure.TooManyRequests(MessageRef(MessageKeys.rateLimited), "Too many attempts. Try again later.")
  }

  private val invalidCredentials: ApiFailure.Unauthorized = {
    ApiFailure.Unauthorized(MessageRef(MessageKeys.invalidCredentials), "Invalid email or password")
  }

  /** The shape every `ValidationError` takes: a generic top-level message, with the real content in `fieldErrors` so a
    * form can show each problem under the input that caused it.
    */
  private def validationFailed(fieldErrors: Map[String, MessageRef]): ApiFailure.BadRequest = {
    ApiFailure.BadRequest(MessageRef(MessageKeys.validationFailed), "Validation failed", fieldErrors)
  }

  def auth(
    failure: AuthFailure
  ): ApiFailure.BadRequest | ApiFailure.Unauthorized | ApiFailure.Conflict | ApiFailure.TooManyRequests = {
    failure match {
      case AuthFailure.InvalidCredentials           =>
        invalidCredentials
      case AuthFailure.EmailAlreadyRegistered       =>
        emailAlreadyRegistered
      case AuthFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case AuthFailure.RateLimited                  =>
        rateLimited
      case AuthFailure.OAuthFailed(reason)          =>
        ApiFailure.BadRequest(MessageRef(MessageKeys.oauthFailed, List(reason)), s"Sign-in failed: $reason")
      case AuthFailure.OAuthAccountExists(provider) =>
        ApiFailure.Conflict(
          MessageRef(MessageKeys.oauthAccountExists, List(provider.display)),
          s"An account with this email already exists. Sign in with your password, " +
            s"then link ${provider.display} from Settings.",
        )
      case AuthFailure.OAuthAlreadyLinked           =>
        ApiFailure.Conflict(MessageRef(MessageKeys.oauthAlreadyLinked), "That account is already linked")
      case AuthFailure.LastCredential               =>
        ApiFailure.Conflict(
          MessageRef(MessageKeys.lastCredential),
          "Set a password first — this is the only way left to sign in to this account",
        )
      case AuthFailure.EmailNotVerified             =>
        // Unreachable through this mapping: only `login` can raise it, and `login` uses
        // `authLogin` below to answer 403 instead. Mapped anyway because the match is exhaustive,
        // and to 401 because that is the only status every endpoint on this mapping declares.
        invalidCredentials
      case AuthFailure.InvalidVerificationToken     =>
        verificationTokenInvalid
      case AuthFailure.InvalidPasswordResetToken    =>
        // Unreachable through this mapping, for the same reason `EmailNotVerified` is above: only
        // `resetPassword` can raise it, and it uses `resetPassword` below instead. Mapped anyway to
        // keep the match total.
        passwordResetTokenInvalid
      case AuthFailure.CaptchaRequired              =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.captchaRequired),
          "Please complete the captcha verification",
        )
      case AuthFailure.CaptchaFailed                =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.captchaFailed),
          "Captcha verification failed. Please try again.",
        )
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
        ApiFailure.Forbidden(
          MessageRef(MessageKeys.emailNotVerified),
          "Verify your email address before signing in",
        )
      case other                        =>
        auth(other)
    }
  }

  /** Redeeming a verification link: the only failure [[gathedge.backend.service.AuthService.verifyEmail]] raises is
    * `InvalidVerificationToken`, and 400 is the only status that endpoint describes. The catch-all is a bug in the
    * service rather than a request the caller got wrong — answering it as a 400 keeps it inside the description, where
    * a wider union would instead fail at encode time.
    */
  def verifyEmail(failure: AuthFailure): ApiFailure.BadRequest = {
    failure match {
      case AuthFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
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
        rateLimited
      case AuthFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case _                                        =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.verificationSendFailed),
          "Could not send a verification link",
        )
    }
  }

  /** Asking for a password-reset link. Succeeds for an unknown address too, so the rate limiter's 429 is the only
    * failure a caller can actually see; see [[resendVerification]], which this mirrors.
    */
  def forgotPassword(failure: AuthFailure): ApiFailure.BadRequest | ApiFailure.TooManyRequests = {
    failure match {
      case AuthFailure.RateLimited                  =>
        rateLimited
      case AuthFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case _                                        =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.passwordResetSendFailed),
          "Could not send a password reset link",
        )
    }
  }

  /** Redeeming a password-reset link: either the token is invalid, or the new password fails validation. Mirrors
    * [[verifyEmail]] — a catch-all mapped to the token failure keeps the match total without a status this endpoint
    * does not describe.
    */
  def resetPassword(failure: AuthFailure): ApiFailure.BadRequest = {
    failure match {
      case AuthFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case _                                        =>
        passwordResetTokenInvalid
    }
  }

  def admin(failure: AdminFailure): ApiFailure.BadRequest | ApiFailure.NotFound | ApiFailure.Conflict = {
    failure match {
      case AdminFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case AdminFailure.DuplicateEmail               =>
        emailAlreadyRegistered
      case AdminFailure.NotFound                     =>
        ApiFailure.NotFound(MessageRef(MessageKeys.adminUserNotFound), "User not found")
      case AdminFailure.SelfDemote                   =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.adminSelfDemote),
          "You cannot remove your own administrator privileges",
        )
      case AdminFailure.SelfDelete                   =>
        ApiFailure.BadRequest(MessageRef(MessageKeys.adminSelfDelete), "You cannot delete your own account")
      case AdminFailure.LastCredential               =>
        ApiFailure.Conflict(
          MessageRef(MessageKeys.adminLastCredential),
          "That is the account's only way to sign in; give it a password first",
        )
    }
  }

  def word(failure: WordFailure): ApiFailure.BadRequest | ApiFailure.NotFound | ApiFailure.Conflict = {
    failure match {
      case WordFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case WordFailure.NotFound                     =>
        ApiFailure.NotFound(MessageRef(MessageKeys.wordNotFound), "No such word")
      case WordFailure.TagNotFound                  =>
        // Somebody else's tag id answers the same as one that does not exist: whose a given id is is not something an
        // account may learn by trying.
        ApiFailure.NotFound(MessageRef(MessageKeys.wordTagNotFound), "No such tag")
      case WordFailure.DuplicateTag                 =>
        ApiFailure.Conflict(MessageRef(MessageKeys.wordTagExists), "You already have a tag with that name")
      case WordFailure.DuplicateTranslation         =>
        ApiFailure.Conflict(
          MessageRef(MessageKeys.wordTranslationExists),
          "You have already added that translation",
        )
      case WordFailure.TagQuotaExceeded(limit)      =>
        ApiFailure.Conflict(
          MessageRef(MessageKeys.wordTagQuotaExceeded, List(limit.toString)),
          s"You've reached the maximum of $limit tags for your account",
        )
      case WordFailure.PairQuotaExceeded(limit)     =>
        ApiFailure.Conflict(
          MessageRef(MessageKeys.wordPairQuotaExceeded, List(limit.toString)),
          s"You've reached the maximum of $limit practice pairs for your account",
        )
    }
  }

  // GameFailure gets six mappings rather than one: create only ever raises NoTagsSelected/TagNotEligible/
  // ValidationError (all BadRequest), get only ever raises NotFound, rename can raise NotFound/NotOwner/
  // ValidationError, startPlay can raise NotFound/NoEligibleWords, reshuffle can raise NotFound/NotOwner/
  // NotFixedPool, and the three play-id endpoints (nextPrompt/submitAnswer/getResults) can raise NotFound/NotOwner.
  // A single wide mapping would force every one of them to describe statuses they cannot produce — the same reason
  // the guest mappings below are four functions instead of one.

  def game(failure: GameFailure): ApiFailure.NotFound = {
    failure match {
      case GameFailure.NotFound =>
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
      case _                    =>
        // Unreachable through this mapping: getBySlug only ever raises NotFound. Mapped anyway to keep the match total.
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
    }
  }

  def gameCreate(failure: GameFailure): ApiFailure.BadRequest = {
    failure match {
      case GameFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case GameFailure.NoTagsSelected               =>
        ApiFailure.BadRequest(MessageRef(MessageKeys.gameNoTagsSelected), "Select at least one tag")
      case GameFailure.TagNotEligible               =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.gameTagNotEligible),
          "One of the selected tags has no eligible pairs for this language pair",
        )
      case _                                        =>
        // Unreachable through this mapping: createGame never raises NotFound/NotOwner. Mapped anyway to keep the
        // match total.
        ApiFailure.BadRequest(MessageRef(MessageKeys.validationFailed), "Validation failed")
    }
  }

  def gameRename(failure: GameFailure): ApiFailure.BadRequest | ApiFailure.Forbidden | ApiFailure.NotFound = {
    failure match {
      case GameFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case GameFailure.NotOwner                     =>
        ApiFailure.Forbidden(MessageRef(MessageKeys.gameNotOwner), "You do not own this game")
      case GameFailure.NotFound                     =>
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
      case _                                        =>
        // Unreachable through this mapping: rename never raises NoTagsSelected/TagNotEligible. Mapped anyway to
        // keep the match total.
        ApiFailure.BadRequest(MessageRef(MessageKeys.validationFailed), "Validation failed")
    }
  }

  /** Starting a play: either the slug is unknown, or the game's tags currently carry nothing eligible to play. */
  def gameStartPlay(failure: GameFailure): ApiFailure.BadRequest | ApiFailure.NotFound = {
    failure match {
      case GameFailure.NotFound        =>
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
      case GameFailure.NoEligibleWords =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.gameNoEligibleWords),
          "This game has no eligible words to play right now",
        )
      case _                           =>
        // Unreachable through this mapping: startPlay never raises NoTagsSelected/TagNotEligible/ValidationError/
        // NotOwner. Mapped anyway to keep the match total.
        ApiFailure.BadRequest(MessageRef(MessageKeys.validationFailed), "Validation failed")
    }
  }

  /** Reshuffling: an unknown slug, one that belongs to somebody else, or a game with nothing fixed to reshuffle
    * (`randomizeEachPlay = true`, or no word limit at all).
    */
  def gameReshuffle(failure: GameFailure): ApiFailure.Conflict | ApiFailure.Forbidden | ApiFailure.NotFound = {
    failure match {
      case GameFailure.NotOwner     =>
        ApiFailure.Forbidden(MessageRef(MessageKeys.gameNotOwner), "You do not own this game")
      case GameFailure.NotFound     =>
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
      case GameFailure.NotFixedPool =>
        ApiFailure.Conflict(
          MessageRef(MessageKeys.gameNotFixedPool),
          "This game has nothing fixed to reshuffle",
        )
      case _                        =>
        // Unreachable through this mapping. Mapped anyway to keep the match total.
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
    }
  }

  /** `nextPrompt`/`submitAnswer`/`getResults`: an unknown `playId`, or one that belongs to somebody else. */
  def gamePlay(failure: GameFailure): ApiFailure.Forbidden | ApiFailure.NotFound = {
    failure match {
      case GameFailure.NotOwner =>
        ApiFailure.Forbidden(MessageRef(MessageKeys.gameNotOwner), "You do not own this game")
      case GameFailure.NotFound =>
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
      case _                    =>
        // Unreachable through this mapping. Mapped anyway to keep the match total.
        ApiFailure.NotFound(MessageRef(MessageKeys.gameNotFound), "No such game")
    }
  }

  // The guest paths get four mappings rather than one, because their four failure enums exist for exactly that reason:
  // a single mapping would return the union of every status any of them can produce, and each endpoint would then have
  // to describe a 403 it cannot raise or a 409 it has no notion of. See the enums in `AuthService`.

  private val notGuest: ApiFailure.Forbidden = {
    ApiFailure.Forbidden(MessageRef(MessageKeys.guestNotGuest), "This account is already registered")
  }

  def guestMint(failure: GuestMintFailure): ApiFailure.TooManyRequests = {
    failure match {
      case GuestMintFailure.RateLimited =>
        rateLimited
    }
  }

  def guestClaim(failure: GuestClaimFailure): ApiFailure.NotFound | ApiFailure.TooManyRequests = {
    failure match {
      case GuestClaimFailure.RateLimited =>
        rateLimited
      case GuestClaimFailure.InvalidCode =>
        // Unknown and revoked answer alike, so the code space cannot be probed.
        ApiFailure.NotFound(MessageRef(MessageKeys.guestCodeInvalid), "That transfer code is not valid")
    }
  }

  def guestCode(failure: GuestCodeFailure): ApiFailure.Forbidden = {
    failure match {
      case GuestCodeFailure.NotGuest =>
        notGuest
    }
  }

  def guestUpgrade(
    failure: GuestAccountFailure
  ): ApiFailure.BadRequest | ApiFailure.Forbidden | ApiFailure.Conflict = {
    failure match {
      case GuestAccountFailure.NotGuest                     =>
        notGuest
      case GuestAccountFailure.ValidationError(fieldErrors) =>
        validationFailed(fieldErrors)
      case GuestAccountFailure.EmailAlreadyRegistered       =>
        emailAlreadyRegistered
      case GuestAccountFailure.CaptchaRequired              =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.captchaRequired),
          "Please complete the captcha verification",
        )
      case GuestAccountFailure.CaptchaFailed                =>
        ApiFailure.BadRequest(
          MessageRef(MessageKeys.captchaFailed),
          "Captcha verification failed. Please try again.",
        )
    }
  }
}
