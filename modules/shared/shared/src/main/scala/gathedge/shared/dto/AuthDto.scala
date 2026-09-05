package gathedge.shared.dto

import zio.json.*
import gathedge.shared.domain.{Locale, OAuthProvider, Theme, User}
import gathedge.shared.i18n.MessageRef

/** `captchaToken` is optional because captcha is a deployment choice, not a wire contract: when `captcha` is
  * unconfigured the frontend sends no token and the server never asks for one. When it is configured, signup always
  * requires it, so the field is filled on every real request.
  */
final case class SignupRequest(email: String, password: String, captchaToken: Option[String] = None) derives JsonCodec

/** `identifier` is the address *or* the username — a sign-in may name the account either way, and the two are told
  * apart by the `@` a username may not contain. It is not called `email` any more precisely because it is no longer
  * always one.
  *
  * `captchaToken` is optional for the opposite reason to signup's: login only demands a captcha once the same client
  * address has made `captcha.login-threshold` failed attempts. Until then it is absent.
  */
final case class LoginRequest(identifier: String, password: String, captchaToken: Option[String] = None)
    derives JsonCodec

final case class UpdateThemeRequest(theme: Theme) derives JsonCodec

/** The account's own profile, both halves optional and both replaced wholesale: `None` clears the field rather than
  * leaving it alone, so the settings form can empty a username by sending an empty box.
  *
  * The username is normalised server-side (`Validation.normalizeUsername`), so what comes back on the `AuthResponse`
  * may differ in case from what was sent.
  */
final case class UpdateProfileRequest(username: Option[String], name: Option[String]) derives JsonCodec

/** Records the language the account has chosen. Note this does *not* change what the caller sees: the language of a
  * page is decided by the URL prefix it was loaded under, and switching languages is a navigation to the other prefix.
  * This persists the choice so it survives to a new browser, and so email can be written in it.
  */
final case class UpdateLocaleRequest(locale: Locale) derives JsonCodec

final case class AuthResponse(user: User) derives JsonCodec

/** Signup's own response, because signup is the one place where succeeding does not always mean being signed in.
  *
  * With `app.require-email-verification` on, the account is created but gets no session until the emailed link is
  * followed. The browser cannot tell that from the response headers — `Set-Cookie` is a forbidden response header, so a
  * missing one is invisible to `fetch` — hence `signedIn` as an ordinary field.
  */
final case class SignupResponse(user: User, signedIn: Boolean) derives JsonCodec

/** The token out of a verification link, posted back by the SPA page the link lands on. */
final case class VerifyEmailRequest(token: String) derives JsonCodec

/** Asks for a fresh verification link. Carries the address rather than reading it off a session: the account that needs
  * one is typically the account that cannot sign in.
  */
final case class ResendVerificationRequest(email: String, captchaToken: Option[String] = None) derives JsonCodec

/** Asks for a password-reset link. Answers the same for an unknown address and a known one alike, per
  * [[gathedge.shared.api.AuthEndpoints.forgotPassword]].
  */
final case class ForgotPasswordRequest(email: String, captchaToken: Option[String] = None) derives JsonCodec

/** The token out of a password-reset link, plus the password it should set. Carries the token rather than reading a
  * session, because the whole point is that the caller has no session yet — possibly no memory of the old password
  * either.
  */
final case class ResetPasswordRequest(token: String, newPassword: String) derives JsonCodec

/** One social account linked to the signed-in user. `email` is whatever the provider reported when the link was made,
  * shown so the user can tell two accounts at the same provider apart; it is never what a sign-in matches on.
  */
final case class LinkedIdentity(provider: OAuthProvider, email: Option[String], createdAt: String) derives JsonCodec

/** Everything the settings page needs to decide what it may offer.
  *
  * `hasPassword` is false for an account created through a social sign-in, which is what turns the password form into
  * "set a password" instead of "change password". `available` is the providers the server actually has credentials for,
  * so the page never shows a button whose route would 404.
  */
final case class IdentitiesResponse(
  identities: List[LinkedIdentity],
  hasPassword: Boolean,
  available: List[OAuthProvider],
) derives JsonCodec

/** `currentPassword` is required only when the account already has one — a social-only account is setting its first. */
final case class SetPasswordRequest(currentPassword: Option[String], newPassword: String) derives JsonCodec

/** The social providers this deployment has credentials for. Read by the sign-in and sign-up forms, which have no
  * session yet and so cannot get the same list from [[IdentitiesResponse]].
  */
final case class ProvidersResponse(providers: List[OAuthProvider]) derives JsonCodec

/** What the captcha-gated forms need to decide whether to render the Turnstile widget.
  *
  * `siteKey` is `None` when captcha is unconfigured, and every form then skips the widget. When it is present the
  * sign-up, forgot-password and resend forms always render it, while the sign-in form only does once `loginFailures`
  * (the client address's failed-attempt count inside the rate-limit window) reaches `loginThreshold`.
  *
  * `loginFailures` is the caller's own address's count, not somebody else's, so there is nothing sensitive to hide by
  * rounding it.
  */
final case class CaptchaStatusResponse(siteKey: Option[String], loginFailures: Int, loginThreshold: Int)
    derives JsonCodec

/** RFC-7807-flavored problem response for validation/auth errors.
  *
  * Field-for-field identical to every `api.ApiFailure` case, because the aspects in `RouteSupport` and the two OAuth
  * routes build their bodies from this by hand rather than through an endpoint's codecs, and the two must be
  * indistinguishable on the wire. `ApiEndpointsSpec` pins that on real bytes.
  */
final case class ErrorResponse(
  error: MessageRef,
  message: String,
  fieldErrors: Map[String, MessageRef] = Map.empty,
) derives JsonCodec
