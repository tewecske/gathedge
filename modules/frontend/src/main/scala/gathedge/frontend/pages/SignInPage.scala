package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.components.{AppShell, CaptchaField, ClaimCodeForm, OAuthButtons, OAuthMessages}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.domain.OAuthProvider
import gathedge.shared.dto.{AuthResponse, CaptchaStatusResponse, LoginRequest, ProvidersResponse}
import gathedge.shared.i18n.{MessageKeys, UiKeys}

object SignInPage {

  /** The signed-out shell, so the wordmark, the language picker and the theme control are in the same corner here as
    * they are once signed in — and so this page carries no picker of its own.
    */
  def render(): HtmlElement = AppShell.renderPublic(new SignInPage().render())
}

private class SignInPage {
  private val emailVar       = Var("")
  private val emailSignal    = emailVar.signal
  private val passwordVar    = Var("")
  private val passwordSignal = passwordVar.signal

  /** Seeded from `?error=` so a failed OAuth round trip explains itself: the callback redirects here on failure, and
    * without this the user lands back on a blank form with no idea why.
    */
  private val errorVar: Var[Option[String]] = Var(OAuthMessages.queryParam("error").map(OAuthMessages.errorMessage))
  private val errorSignal                   = errorVar.signal

  /** Seeded the same way from `?verified=1` (where [[VerifyEmailPage]] sends a freshly verified account) or `?reset=1`
    * (where [[ResetPasswordPage]] sends one whose password just changed).
    */
  private val noticeVar: Var[Option[String]] = {
    Var(
      OAuthMessages
        .queryParam("verified")
        .map(_ => I18n.t(UiKeys.signInVerified))
        .orElse(OAuthMessages.queryParam("reset").map(_ => I18n.t(UiKeys.signInPasswordReset)))
    )
  }
  private val noticeSignal                   = noticeVar.signal

  /** Set when the server answers 403, i.e. the password was right but the address is unverified. That is the only
    * moment offering a resend makes sense — before it, the address may not even have an account.
    */
  private val canResendVar                           = Var(false)
  private val inFlightVar                            = Var(false)
  private val inFlightSignal                         = inFlightVar.signal
  private val resendBus                              = new EventBus[Unit]()
  private val providersVar: Var[List[OAuthProvider]] = Var(Nil)
  private val providersSignal                        = providersVar.signal
  private val hasProvidersSignal                     = providersSignal.map(_.nonEmpty).distinct

  /** The captcha state. `captchaTokenVar` holds the single-use token the widget last produced; `captchaResetBus` asks
    * the widget for a fresh one (a spent token must not be re-sent), and `refreshCaptchaBus` re-reads the
    * failed-attempt count from the server, which is what turns the widget on after the threshold is crossed.
    */
  private val captchaStatusVar: Var[Option[CaptchaStatusResponse]] = Var(None)
  private val captchaTokenVar: Var[Option[String]]                 = Var(None)
  private val captchaResetBus                                      = new EventBus[Unit]()
  private val refreshCaptchaBus                                    = new EventBus[Unit]()

  /** The sign-in form shows the widget once this address has crossed the failed-attempt threshold, or once a resend is
    * on offer (the resend endpoint is captcha-gated always, so the widget has to be present for its token to come
    * from).
    */
  private val captchaSiteKeySignal: Signal[Option[String]] = {
    captchaStatusVar.signal
      .combineWithFn(canResendVar.signal) { (status, canResend) =>
        for {
          s   <- status
          key <- s.siteKey
          if s.loginFailures >= s.loginThreshold || canResend
        } yield key
      }
      .distinct
  }

  private lazy val socialBlock: HtmlElement = {
    div(div(cls := "divider text-xs", I18n.t(UiKeys.commonOr)), OAuthButtons.render(providersSignal))
  }

  private val submitBus = new EventBus[Unit]()

  // `flatMapSwitch` discards a superseded response, but the request has already been sent —
  // gating the stream on the in-flight flag is what actually prevents a double submit.
  private val submitStream = submitBus.events.filterWith(inFlightSignal.not)

  /** A guest reaching this page (the `RequireAnon` guard exempts it, same as [[SignUpPage]]) already has the only
    * credential it needs — offering to claim a *different* device's transfer code here is a distraction, not a recovery
    * path.
    */
  private val isGuestSignedIn: Boolean = AppState.currentUser.exists(_.isGuest)

  def render(): HtmlElement = {
    div(
      cls := "w-full max-w-sm",
      // A real form element, so Enter in either field submits.
      form(
        cls := "card w-full bg-base-100 shadow-xl",
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        div(
          cls := "card-body",
          h1(cls := "card-title", I18n.t(UiKeys.commonSignIn)),
          child.maybe <-- noticeSignal.map(_.map(renderNotice)),
          child.maybe <-- errorSignal.map(_.map(renderError)),
          child.maybe <-- canResendVar.signal.map(Option.when(_)(resendBlock)),
          fieldSet(
            cls  := "fieldset",
            legend(cls    := "fieldset-legend", I18n.t(MessageKeys.fieldEmail)),
            input(
              cls         := "input w-full",
              typ         := "email",
              placeholder := "you@example.com",
              controlled(value <-- emailSignal, onInput.mapToValue --> emailVar.writer),
            ),
            legend(cls    := "fieldset-legend", I18n.t(MessageKeys.fieldPassword)),
            input(
              cls         := "input w-full",
              typ         := "password",
              controlled(value <-- passwordSignal, onInput.mapToValue --> passwordVar.writer),
            ),
            p(
              cls         := "label justify-end",
              a(cls := "link", AppRouter.router.navigateTo(Page.ForgotPassword), I18n.t(UiKeys.signInForgotPassword)),
            ),
          ),
          child.maybe <-- captchaSiteKeySignal.map(_.map(renderCaptcha)),
          div(
            cls  := "card-actions justify-end mt-4",
            button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, I18n.t(UiKeys.commonSignIn)),
          ),
          // Hidden entirely when no provider is configured, rather than leaving a stray divider
          // above nothing. Built once and shown or hidden, not rebuilt per signal change.
          child.maybe <-- hasProvidersSignal.map(Option.when(_)(socialBlock)),
          Option.when(!isGuestSignedIn)(ClaimCodeForm.render()),
          p(
            cls  := "text-sm mt-2",
            I18n.t(UiKeys.signInNoAccount),
            a(cls := "link", AppRouter.router.navigateTo(Page.SignUp), I18n.t(UiKeys.commonSignUp)),
          ),
        ),
      ),
      ApiClient.providers -->
        Observer[Either[ApiError, ProvidersResponse]] {
          case Right(res) =>
            providersVar.set(res.providers)
          case Left(_)    =>
            // The password form still works; offering no social buttons is the right degraded state.
            providersVar.set(Nil)
        },
      ApiClient.captchaStatus --> captchaStatusObserver,
      refreshCaptchaBus.events.flatMapSwitch(_ => ApiClient.captchaStatus) --> captchaStatusObserver,
      submitStream -->
        Observer[Unit](_ => Var.set(inFlightVar -> true, errorVar -> None, noticeVar -> None, canResendVar -> false)),
      submitStream.flatMapSwitch(_ => login()) -->
        Observer[Either[ApiError, AuthResponse]] {
          case Right(res) =>
            inFlightVar.set(false)
            // No navigation here on purpose: this page is `RequireAnon`, so writing the user into
            // AppState is what sends `App`'s guard observer to Home (via `replaceState`, which also
            // keeps the sign-in form out of the back history). Pushing Home as well made the router
            // emit Home twice, remounting the page and firing its load request a second time.
            AppState.setUser(res.user)
          case Left(err)  =>
            Var.set(
              inFlightVar  -> false,
              errorVar     -> Some(err.message),
              // 403 here means exactly one thing: the credentials were right and the address is
              // not verified. Anything else is a reason a resend would not help with.
              canResendVar -> (err.status == 403),
            )
            // The captcha token (if one was sent) is single-use, and a failure may have just crossed the
            // threshold that turns the widget on. Reset the spent challenge and re-read the count.
            captchaResetBus.writer.onNext(())
            refreshCaptchaBus.writer.onNext(())
        },
      resendBus.events.flatMapSwitch(_ => ApiClient.resendVerification(emailVar.now(), captchaTokenVar.now())) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(
              canResendVar -> false,
              errorVar     -> None,
              noticeVar    -> Some(I18n.t(UiKeys.verificationResent)),
            )
            captchaResetBus.writer.onNext(())
          case Left(err) =>
            errorVar.set(Some(err.message))
            captchaResetBus.writer.onNext(())
        },
    )
  }

  private val captchaStatusObserver = Observer[Either[ApiError, CaptchaStatusResponse]] {
    case Right(status) =>
      captchaStatusVar.set(Some(status))
    case Left(_)       =>
      // Captcha unconfigured or the status call failed: no site key, no widget, no token.
      captchaStatusVar.set(None)
  }

  private def renderCaptcha(siteKey: String): HtmlElement = {
    new CaptchaField(siteKey, captchaTokenVar.writer, captchaResetBus.events).render()
  }

  /** Deliberately not wired to the notice above: a resend only ever reports that *something* was sent, never whether
    * the address has an account, so the copy stays the same as the one [[CheckInboxPage]] shows.
    */
  private lazy val resendBlock: HtmlElement = {
    div(
      cls := "mt-2",
      button(
        cls := "btn btn-outline btn-sm w-full",
        typ := "button",
        onClick.mapToUnit --> resendBus.writer,
        I18n.t(UiKeys.verificationResendButton),
      ),
    )
  }

  private def login(): EventStream[Either[ApiError, AuthResponse]] = {
    ApiClient.login(LoginRequest(emailVar.now(), passwordVar.now(), captchaTokenVar.now()))
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }

  private def renderNotice(message: String): HtmlElement = {
    div(role := "status", cls := "alert alert-success", span(message))
  }
}
