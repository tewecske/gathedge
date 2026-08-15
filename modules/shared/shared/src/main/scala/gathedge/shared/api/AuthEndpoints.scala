package gathedge.shared.api

import gathedge.shared.dto.{
  AuthResponse,
  ClaimCodeResponse,
  ClaimRequest,
  IdentitiesResponse,
  LoginRequest,
  ProvidersResponse,
  ResendVerificationRequest,
  SetPasswordRequest,
  SignupRequest,
  SignupResponse,
  UpdateLocaleRequest,
  UpdateThemeRequest,
  UpgradeRequest,
  VerifyEmailRequest,
}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure, sessionCookie, withCodecError}
import ApiSchemas.given

/** Sign-up, sign-in, sign-out and the signed-in user's own record.
  *
  * The two Google OAuth routes are *not* here. They are top-level browser navigations whose success and failure are
  * both redirects rather than bodies, so they stay on the imperative DSL in `AuthRoutes` — see the note there.
  *
  * These are the only endpoints in the API that declare 429: the rate limiter lives in `AuthService` and signup, login
  * and the verification resend are all that go through it.
  *
  * [[login]] is the API's only path that declares 403, and it does so for the reason those do: the *service* raises it
  * rather than an aspect. `AuthFailure.EmailNotVerified` is an answer to a well-formed request with the right password,
  * and the sign-in form renders it as an offer to resend the link.
  */
object AuthEndpoints {

  /** Answers `SignupResponse` rather than `AuthResponse` because a successful signup does not always sign anybody in:
    * with `app.require-email-verification` on, the account exists but has no session until the emailed link is
    * followed, and `signedIn` is the only way the browser can tell (a missing `Set-Cookie` is invisible to `fetch`).
    */
  val signup = {
    Endpoint(Method.POST / "api" / "auth" / "signup")
      .in[SignupRequest]
      .withCodecError
      .out[SignupResponse](Status.Created)
      .outHeader(sessionCookie)
      .outErrors(failure.badRequest, failure.unauthorized, failure.conflict, failure.tooManyRequests)
  }

  /** 403 is `AuthFailure.EmailNotVerified`: the password was right, but the address has never been proven and this
    * deployment requires that. Distinct from the 401 a wrong password gets, so the form can offer a resend rather than
    * telling the user to check their typing.
    */
  val login = {
    Endpoint(Method.POST / "api" / "auth" / "login")
      .in[LoginRequest]
      .withCodecError
      .out[AuthResponse]
      .outHeader(sessionCookie)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.conflict, failure.tooManyRequests)
  }

  /** Redeems the token out of a verification link. Public: the whole point is that the account it verifies usually
    * cannot sign in yet. 400 covers a token that is unknown, already used, or expired — one answer for all three, so
    * the token space cannot be probed.
    */
  val verifyEmail = {
    Endpoint(Method.POST / "api" / "auth" / "verify")
      .in[VerifyEmailRequest]
      .withCodecError
      .outCodec(HttpCodec.status(Status.NoContent))
      .outFailure(failure.badRequest)
  }

  /** Sends a fresh verification link. Answers 204 for an unknown address and an already-verified one alike — saying
    * which is which would make this an account-enumeration oracle — so the only failure a caller ever sees is the rate
    * limiter's 429.
    */
  val resendVerification = {
    Endpoint(Method.POST / "api" / "auth" / "verification" / "resend")
      .in[ResendVerificationRequest]
      .withCodecError
      .outCodec(HttpCodec.status(Status.NoContent))
      .outErrors(failure.badRequest, failure.tooManyRequests)
  }

  /** Answers 204 whether or not there was a session to end, and always sends the already-expired cookie back.
    *
    * The success is described as a bare status rather than `.out[Unit](Status.NoContent)` for the reason recorded on
    * [[AdminEndpoints.deleteUser]]: `out[Unit]` installs a body codec that needs `Content-Length: 0` to recognise an
    * empty body, which a 204 must not send.
    *
    * Logging out without a session is a no-op rather than a failure, so the handler cannot fail — and the two statuses
    * that could still come back (the CSRF aspect's 403, a defect's 500) are not described here, for the reason recorded
    * on [[ApiEndpoint.failure]]. This is consequently the one endpoint in the API that declares no failure at all.
    */
  val logout = {
    Endpoint(Method.POST / "api" / "auth" / "logout")
      .outCodec(HttpCodec.status(Status.NoContent))
      .outHeader(sessionCookie)
  }

  /** Reads back the `User` the `authenticated` aspect already resolved, so that aspect's 401 is the only failure a
    * caller can act on.
    */
  val me = {
    Endpoint(Method.GET / "api" / "me").out[AuthResponse].outFailure(failure.unauthorized)
  }

  /** `AuthService.updateTheme` is `.orDie`'d in the handler — a failure there is a bug or a dead database, not
    * something the caller can act on — so the declared failures are the `authenticated` aspect's 401 and a 400 that no
    * handler raises: it is reachable only by the request codec rejecting the body, which `ApiEndpoint.withCodecError`
    * answers. Declaring it is what makes that a value the client can act on rather than a defect, since a status a
    * description omits is not decodable at all.
    */
  val updateTheme = {
    Endpoint(Method.PUT / "api" / "me" / "theme")
      .in[UpdateThemeRequest]
      .withCodecError
      .out[AuthResponse]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Persists the account's chosen language. Same failure shape and same reasoning as [[updateTheme]], which this is
    * modelled on: the service call is `.orDie`'d, so the 400 exists only for a body the codec rejects.
    */
  val updateLocale = {
    Endpoint(Method.PUT / "api" / "me" / "locale")
      .in[UpdateLocaleRequest]
      .withCodecError
      .out[AuthResponse]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Which social providers this deployment has credentials for, so the sign-in form only offers buttons whose flow can
    * actually complete.
    *
    * Public, and necessarily so: it is read before anyone has signed in. That makes it the only endpoint here with no
    * declared failure other than none at all — it takes no input, so no codec error is reachable, and no aspect guards
    * it. The list is not a secret; a provider button is visible to every visitor anyway.
    */
  val providers = {
    Endpoint(Method.GET / "api" / "auth" / "providers").out[ProvidersResponse]
  }

  /** Carried as a plain string rather than a codec over `OAuthProvider`, so an unknown segment is a 400 the handler
    * raises with a message naming what it wanted, rather than a path that simply fails to match and falls through to
    * the catch-all 404.
    */
  private val provider = PathCodec.string("provider")

  /** What the settings page needs to render itself: the linked social accounts, whether a password is set, and which
    * providers this deployment actually has credentials for.
    */
  val identities = {
    Endpoint(Method.GET / "api" / "me" / "identities").out[IdentitiesResponse].outFailure(failure.unauthorized)
  }

  /** 409 covers the lockout guard: unlinking the account's last remaining credential is refused, since there would be
    * no password and no other provider left to sign in with. 400 covers both an unparseable provider segment and one
    * that is simply not linked — `AuthFailure` has no `NotFound` case, and adding one would widen `ApiFailures.auth`'s
    * union onto signup and login, which cannot raise it.
    */
  val unlinkIdentity = {
    Endpoint(Method.DELETE / "api" / "me" / "identities" / provider).withCodecError
      .outCodec(HttpCodec.status(Status.NoContent))
      .outErrors(failure.badRequest, failure.unauthorized, failure.conflict)
  }

  /** Sets the first password on a social-only account, or changes an existing one. Both cases answer 400 with
    * `fieldErrors` — a wrong current password is reported against `currentPassword`, a too-weak new one against
    * `newPassword` — so the form can put each message under its own input.
    */
  val setPassword = {
    Endpoint(Method.PUT / "api" / "me" / "password")
      .in[SetPasswordRequest]
      .withCodecError
      .outCodec(HttpCodec.status(Status.NoContent))
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Mints an account with no address and no password, and signs the caller in as it.
    *
    * Public, and the one endpoint in the API that creates an account without anybody asking to register. The browser
    * calls it lazily — on the first *write* a visitor performs, never on a page view, because a session minted per
    * visit is a row per crawler.
    *
    * Takes the visitor's current theme as input, so the account it mints starts on the preference already showing in
    * this browser rather than a hardcoded default — a signed-in `setUser` always trusts the server's answer, so without
    * this the server's default would silently overwrite whatever the visitor had chosen before minting. 400 covers a
    * body that fails to decode; 429 is `AuthService`'s own rate limiter, under its own key namespace, which is not
    * tidiness but the rule the rest of the service follows — sharing a namespace with sign-in is what once let a burst
    * of failures lock every account out.
    */
  val createGuest = {
    Endpoint(Method.POST / "api" / "guest")
      .in[UpdateThemeRequest]
      .withCodecError
      .out[AuthResponse](Status.Created)
      .outHeader(sessionCookie)
      .outErrors(failure.badRequest, failure.tooManyRequests)
  }

  /** Answers the guest account's transfer code, minting one on the account's first call and the same one again on every
    * call after — the account menu can ask for it any time without minting a fresh code each time.
    *
    * 403 is `GuestFailure.NotGuest`: a real account has an address and a password to sign in with, so it has no use for
    * a bearer code and is not allowed to mint one. It is declared for the same reason [[login]]'s 403 is — the
    * *service* raises it, and it is an answer to a well-formed request rather than an aspect's rejection.
    */
  val guestCode = {
    Endpoint(Method.POST / "api" / "guest" / "code")
      .out[ClaimCodeResponse]
      .outErrors(failure.unauthorized, failure.forbidden)
  }

  /** Signs the caller in as the guest account a transfer code belongs to.
    *
    * Public: the whole point is that the caller is on a machine that has never held that session. 404 covers a code
    * that is unknown, revoked or simply mistyped — one answer for all three, so the code space cannot be probed — and
    * 429 is its own rate-limit namespace, since this is the one endpoint where guessing is worth an attacker's time.
    */
  val claimGuest = {
    Endpoint(Method.POST / "api" / "guest" / "claim")
      .in[ClaimRequest]
      .withCodecError
      .out[AuthResponse]
      .outHeader(sessionCookie)
      .outErrors(failure.badRequest, failure.notFound, failure.tooManyRequests)
  }

  /** Turns the caller's guest account into a real one, in place: the address and password land on the same row, so
    * every tag and word it holds is still there under the same id afterwards.
    *
    * 409 is an address already registered, exactly as on [[signup]]; 403 is `GuestFailure.NotGuest`, since a real
    * account has nothing to upgrade.
    */
  val upgradeGuest = {
    Endpoint(Method.POST / "api" / "auth" / "upgrade")
      .in[UpgradeRequest]
      .withCodecError
      .out[AuthResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.conflict)
  }

  /** For `DocsRoutes`, which needs every description as one heterogeneous collection to generate the OpenAPI document
    * from. Nothing else should reach for it — the implementations and the client name the endpoints individually.
    */
  val all: List[Endpoint[?, ?, ?, ?, ?]] = {
    List(
      signup,
      login,
      logout,
      me,
      updateTheme,
      updateLocale,
      providers,
      identities,
      unlinkIdentity,
      setPassword,
      verifyEmail,
      resendVerification,
      createGuest,
      guestCode,
      claimGuest,
      upgradeGuest,
    )
  }

  /** The two guest endpoints that answer without a session, for `DocsRoutes.publicEndpoints`. */
  val publicGuest: List[Endpoint[?, ?, ?, ?, ?]] = List(createGuest, claimGuest)
}
