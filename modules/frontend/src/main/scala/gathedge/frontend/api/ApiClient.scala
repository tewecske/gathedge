package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.AuthEndpoints
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

import EndpointClient.{executor, run}
import OAuthProvider.wire

/** Every non-admin API call the pages make, generated from the endpoint descriptions in `shared` rather than written by
  * hand — see [[EndpointClient]]. There are no path strings or method names in this file; the admin resource is the
  * same, through [[AdminApiClient]].
  *
  * The signature is uniformly `EventStream[Either[ApiError, A]]`: callers `flatMapSwitch` these from a click or submit
  * stream and branch on the `Either`.
  */
object ApiClient {

  // --- Sessions -----------------------------------------------------------------------------------------------

  /** The session cookie the server sets comes back as the second half of the response, and is always dropped here: a
    * browser applies `Set-Cookie` itself and then hides it from `fetch`, so this is `None` in the page no matter what
    * the server sent (`ApiEndpoint.sessionCookie` explains why it is described as optional at all). The cookie is in
    * the jar regardless — that is what the next call authenticates with.
    */
  def signup(request: SignupRequest): EventStream[Either[ApiError, SignupResponse]] = {
    run(executor(AuthEndpoints.signup(request))).map(_.map(_._1))
  }

  /** Redeems the token out of a verification link. Public — the account it verifies typically cannot sign in yet. */
  def verifyEmail(token: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.verifyEmail(VerifyEmailRequest(token)))).map(_.map(_ => ()))
  }

  /** Answers the same whether or not the address has an account, so a page can only ever report "sent". */
  def resendVerification(email: String, captchaToken: Option[String] = None): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.resendVerification(ResendVerificationRequest(email, captchaToken)))).map(_.map(_ => ()))
  }

  def login(request: LoginRequest): EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.login(request))).map(_.map(_._1))
  }

  /** Answers the same whether or not the address has an account, same non-committal shape as [[resendVerification]]. */
  def forgotPassword(email: String, captchaToken: Option[String] = None): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.forgotPassword(ForgotPasswordRequest(email, captchaToken)))).map(_.map(_ => ()))
  }

  /** Redeems a password-reset link. No session comes back — this proves the address controls the reset link, not that
    * whoever clicked it meant to sign in on this device, the same reasoning [[verifyEmail]] follows.
    */
  def resetPassword(token: String, newPassword: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.resetPassword(ResetPasswordRequest(token, newPassword)))).map(_.map(_ => ()))
  }

  def logout: EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.logout(()))).map(_.map(_ => ()))
  }

  def me: EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.me(())))
  }

  def updateTheme(theme: Theme): EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.updateTheme(UpdateThemeRequest(theme))))
  }

  /** Records the choice; it does not change the current page's language. The picker navigates to the other prefix,
    * which is what actually switches languages — see `CurrentLocale`.
    */
  def updateLocale(locale: Locale): EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.updateLocale(UpdateLocaleRequest(locale))))
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
    run(executor(AuthEndpoints.createGuest(UpdateThemeRequest(AppState.currentTheme)))).map(_.map(_._1))
  }

  /** The guest account's transfer code — the same one every time once it exists. */
  def guestCode: EventStream[Either[ApiError, ClaimCodeResponse]] = {
    run(executor(AuthEndpoints.guestCode(())))
  }

  /** Signs the caller in as the guest account a transfer code belongs to. */
  def claimGuest(code: String): EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.claimGuest(ClaimRequest(code)))).map(_.map(_._1))
  }

  /** Turns the caller's guest account into a real one, keeping everything on it. */
  def upgradeGuest(
    email: String,
    password: String,
    captchaToken: Option[String] = None,
  ): EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.upgradeGuest(UpgradeRequest(email, password, captchaToken))))
  }

  // --- Account settings ---------------------------------------------------------------------------------------

  /** Public, and read by the sign-in and sign-up forms before any session exists — which is why it is separate from
    * [[identities]] rather than a field on it.
    */
  def providers: EventStream[Either[ApiError, ProvidersResponse]] = {
    run(executor(AuthEndpoints.providers(())))
  }

  /** Tells a captcha-gated form whether to render the Turnstile widget (the site key) and, for the sign-in form,
    * whether this address has crossed the threshold of failed attempts that turns it on.
    */
  def captchaStatus: EventStream[Either[ApiError, CaptchaStatusResponse]] = {
    run(executor(AuthEndpoints.captchaStatus(())))
  }

  def identities: EventStream[Either[ApiError, IdentitiesResponse]] = {
    run(executor(AuthEndpoints.identities(())))
  }

  def unlinkIdentity(provider: OAuthProvider): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.unlinkIdentity(provider.wire)))
  }

  def setPassword(request: SetPasswordRequest): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.setPassword(request)))
  }

  /** Where the browser must be *navigated* to start a social sign-in — deliberately a URL rather than a call.
    *
    * Everything else in this file is a `fetch` through the endpoint executor. These two cannot be: the flow is a chain
    * of top-level redirects through the provider and back, so it has to be the document that navigates, not an XHR.
    * That also puts them outside the generated client entirely, which is why this is the one place in the frontend that
    * spells out an API path.
    */
  def oauthStartUrl(provider: OAuthProvider, link: Boolean = false): String = {
    // `locale` rides in the query string because this URL is followed by the *document* navigating,
    // not by the generated client, so it carries none of the client's headers — `X-Locale` included.
    // The server tucks it into the `oauth_state` cookie so it survives the trip through the provider
    // and the callback knows which language's page to redirect back into.
    val params = {
      List(Option.when(link)("link=1"), Some(s"locale=${CurrentLocale.value.code}")).flatten
    }
    s"/api/auth/${provider.wire}/start?${params.mkString("&")}"
  }
}
