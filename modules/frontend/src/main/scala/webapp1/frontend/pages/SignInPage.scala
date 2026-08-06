package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.{OAuthButtons, OAuthMessages}
import webapp1.frontend.state.AppState
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.OAuthProvider
import webapp1.shared.dto.{AuthResponse, LoginRequest, ProvidersResponse}

object SignInPage {
  def render(): HtmlElement = new SignInPage().render()
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

  /** Seeded the same way from `?verified=1`, which is where [[VerifyEmailPage]] sends a freshly verified account. */
  private val noticeVar: Var[Option[String]] = {
    Var(OAuthMessages.queryParam("verified").map(_ => "Your email address is verified. Sign in to continue."))
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

  private lazy val socialBlock: HtmlElement = {
    div(div(cls := "divider text-xs", "or"), OAuthButtons.render(providersSignal))
  }

  private val submitBus = new EventBus[Unit]()

  // `flatMapSwitch` discards a superseded response, but the request has already been sent —
  // gating the stream on the in-flight flag is what actually prevents a double submit.
  private val submitStream = submitBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
      // A real form element, so Enter in either field submits.
      form(
        cls := "card w-full max-w-sm bg-base-100 shadow-xl",
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        div(
          cls := "card-body",
          h1(cls := "card-title", "Sign in"),
          child.maybe <-- noticeSignal.map(_.map(renderNotice)),
          child.maybe <-- errorSignal.map(_.map(renderError)),
          child.maybe <-- canResendVar.signal.map(Option.when(_)(resendBlock)),
          fieldSet(
            cls  := "fieldset",
            legend(cls    := "fieldset-legend", "Email"),
            input(
              cls         := "input w-full",
              typ         := "email",
              placeholder := "you@example.com",
              controlled(value <-- emailSignal, onInput.mapToValue --> emailVar.writer),
            ),
            legend(cls    := "fieldset-legend", "Password"),
            input(
              cls         := "input w-full",
              typ         := "password",
              controlled(value <-- passwordSignal, onInput.mapToValue --> passwordVar.writer),
            ),
          ),
          div(
            cls  := "card-actions justify-end mt-4",
            button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Sign in"),
          ),
          // Hidden entirely when no provider is configured, rather than leaving a stray divider
          // above nothing. Built once and shown or hidden, not rebuilt per signal change.
          child.maybe <-- hasProvidersSignal.map(Option.when(_)(socialBlock)),
          p(
            cls  := "text-sm mt-2",
            "No account? ",
            a(cls := "link", AppRouter.router.navigateTo(Page.SignUp), "Sign up"),
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
        },
      resendBus.events.flatMapSwitch(_ => ApiClient.resendVerification(emailVar.now())) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(
              canResendVar -> false,
              errorVar     -> None,
              noticeVar    -> Some("If that address needs verifying, a new link is on its way."),
            )
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
    )
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
        "Resend verification email",
      ),
    )
  }

  private def login(): EventStream[Either[ApiError, AuthResponse]] = {
    ApiClient.login(LoginRequest(emailVar.now(), passwordVar.now()))
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }

  private def renderNotice(message: String): HtmlElement = {
    div(role := "status", cls := "alert alert-success", span(message))
  }
}
