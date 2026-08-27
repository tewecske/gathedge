package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.CurrentLocale
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{Locale, OAuthProvider, Theme}
import gathedge.shared.domain.Locale.code
import gathedge.shared.dto.{
  AuthResponse,
  CaptchaStatusResponse,
  ClaimCodeResponse,
  ClaimRequest,
  ForgotPasswordRequest,
  IdentitiesResponse,
  LoginRequest,
  ProvidersResponse,
  ResendVerificationRequest,
  ResetPasswordRequest,
  SetPasswordRequest,
  SignupRequest,
  SignupResponse,
  UpdateLocaleRequest,
  UpdateThemeRequest,
  UpgradeRequest,
  VerifyEmailRequest,
}
import zio.json._

import OAuthProvider.wire

/** Every non-admin API call the pages make. The path and method are spelled out here; the shapes come from the shared
  * DTOs, and the shared `AuthEndpoints` description stays what the backend and the OpenAPI document are built from —
  * `ApiPathParitySpec` pins that this file still agrees with it.
  *
  * The signature is uniformly `EventStream[Either[ApiError, A]]`: callers `flatMapSwitch` these from a click or submit
  * stream and branch on the `Either`. The admin resource is the same shape, through [[AdminApiClient]].
  */
object ApiClient {

  // --- Sessions -----------------------------------------------------------------------------------------------

  /** The session cookie the server sets is applied by the browser and then hidden from `fetch`, so nothing here reads
    * it. The cookie is in the jar regardless — that is what the next call authenticates with.
    */
  def signup(request: SignupRequest): EventStream[Either[ApiError, SignupResponse]] = {
    HttpClient.post[SignupResponse]("/api/auth/signup", Some(request.toJson))
  }

  /** Redeems the token out of a verification link. Public — the account it verifies typically cannot sign in yet. */
  def verifyEmail(token: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, "/api/auth/verify", Some(VerifyEmailRequest(token).toJson))
  }

  /** Answers the same whether or not the address has an account, so a page can only ever report "sent". */
  def resendVerification(email: String, captchaToken: Option[String] = None): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(
      _.POST,
      "/api/auth/verification/resend",
      Some(ResendVerificationRequest(email, captchaToken).toJson),
    )
  }

  def login(request: LoginRequest): EventStream[Either[ApiError, AuthResponse]] = {
    HttpClient.post[AuthResponse]("/api/auth/login", Some(request.toJson))
  }

  /** Answers the same whether or not the address has an account, same non-committal shape as [[resendVerification]]. */
  def forgotPassword(email: String, captchaToken: Option[String] = None): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, "/api/auth/password/forgot", Some(ForgotPasswordRequest(email, captchaToken).toJson))
  }

  /** Redeems a password-reset link. No session comes back — this proves the address controls the reset link, not that
    * whoever clicked it meant to sign in on this device, the same reasoning [[verifyEmail]] follows.
    */
  def resetPassword(token: String, newPassword: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, "/api/auth/password/reset", Some(ResetPasswordRequest(token, newPassword).toJson))
  }

  def logout: EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, "/api/auth/logout")
  }

  def me: EventStream[Either[ApiError, AuthResponse]] = {
    HttpClient.get[AuthResponse]("/api/me")
  }

  def updateTheme(theme: Theme): EventStream[Either[ApiError, AuthResponse]] = {
    HttpClient.put[AuthResponse]("/api/me/theme", Some(UpdateThemeRequest(theme).toJson))
  }

  /** Records the choice; it does not change the current page's language. The picker navigates to the other prefix,
    * which is what actually switches languages — see `CurrentLocale`.
    */
  def updateLocale(locale: Locale): EventStream[Either[ApiError, AuthResponse]] = {
    HttpClient.put[AuthResponse]("/api/me/locale", Some(UpdateLocaleRequest(locale).toJson))
  }

  // --- Guest accounts -----------------------------------------------------------------------------------------

  /** Mints an account with no credentials and signs the caller in as it.
    *
    * Called on the reader's *first write*, not on load: the page has to be usable without leaving a row behind for
    * everything that opens it. The cookie is handled by the browser, as with [[login]]. Sends the theme already showing
    * in this browser, so the account the server mints starts on it rather than on a server-side default —
    * `AppState.setUser` always trusts the server's answer, so this is what keeps a visitor's preference from being
    * silently overwritten the moment they add their first word.
    */
  def createGuest: EventStream[Either[ApiError, AuthResponse]] = {
    HttpClient.post[AuthResponse]("/api/guest", Some(UpdateThemeRequest(AppState.currentTheme).toJson))
  }

  /** The guest account's transfer code — the same one every time once it exists. */
  def guestCode: EventStream[Either[ApiError, ClaimCodeResponse]] = {
    HttpClient.post[ClaimCodeResponse]("/api/guest/code")
  }

  /** Signs the caller in as the guest account a transfer code belongs to. */
  def claimGuest(code: String): EventStream[Either[ApiError, AuthResponse]] = {
    HttpClient.post[AuthResponse]("/api/guest/claim", Some(ClaimRequest(code).toJson))
  }

  /** Turns the caller's guest account into a real one, keeping everything on it. */
  def upgradeGuest(
    email: String,
    password: String,
    captchaToken: Option[String] = None,
  ): EventStream[Either[ApiError, AuthResponse]] = {
    HttpClient.post[AuthResponse]("/api/auth/upgrade", Some(UpgradeRequest(email, password, captchaToken).toJson))
  }

  // --- Account settings ---------------------------------------------------------------------------------------

  /** Public, and read by the sign-in and sign-up forms before any session exists — which is why it is separate from
    * [[identities]] rather than a field on it.
    */
  def providers: EventStream[Either[ApiError, ProvidersResponse]] = {
    HttpClient.get[ProvidersResponse]("/api/auth/providers")
  }

  /** Tells a captcha-gated form whether to render the Turnstile widget (the site key) and, for the sign-in form,
    * whether this address has crossed the threshold of failed attempts that turns it on.
    */
  def captchaStatus: EventStream[Either[ApiError, CaptchaStatusResponse]] = {
    HttpClient.get[CaptchaStatusResponse]("/api/auth/captcha-status")
  }

  def identities: EventStream[Either[ApiError, IdentitiesResponse]] = {
    HttpClient.get[IdentitiesResponse]("/api/me/identities")
  }

  def unlinkIdentity(provider: OAuthProvider): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/me/identities/${provider.wire}")
  }

  def setPassword(request: SetPasswordRequest): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.PUT, "/api/me/password", Some(request.toJson))
  }

  /** Where the browser must be *navigated* to start a social sign-in — deliberately a URL rather than a call.
    *
    * Everything else in this file is a `fetch`. These two cannot be: the flow is a chain of top-level redirects through
    * the provider and back, so it has to be the document that navigates, not an XHR. That also puts them outside the
    * generated client entirely, which is why this is the one place in the frontend that spells out an API path for a
    * reason other than naming a `fetch` target.
    */
  def oauthStartUrl(provider: OAuthProvider, link: Boolean = false): String = {
    // `locale` rides in the query string because this URL is followed by the *document* navigating,
    // not by the client, so it carries none of the client's headers — `X-Locale` included.
    // The server tucks it into the `oauth_state` cookie so it survives the trip through the provider
    // and the callback knows which language's page to redirect back into.
    val params = {
      List(Option.when(link)("link=1"), Some(s"locale=${CurrentLocale.value.code}")).flatten
    }
    s"/api/auth/${provider.wire}/start?${params.mkString("&")}"
  }
}
