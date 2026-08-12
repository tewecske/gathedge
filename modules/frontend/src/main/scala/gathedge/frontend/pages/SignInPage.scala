package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.components.{AppShell, OAuthButtons, OAuthMessages}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.domain.OAuthProvider
import gathedge.shared.dto.{AuthResponse, LoginRequest, ProvidersResponse}
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

  /** Seeded the same way from `?verified=1`, which is where [[VerifyEmailPage]] sends a freshly verified account. */
  private val noticeVar: Var[Option[String]] = {
    Var(OAuthMessages.queryParam("verified").map(_ => I18n.t(UiKeys.signInVerified)))
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
    div(div(cls := "divider text-xs", I18n.t(UiKeys.commonOr)), OAuthButtons.render(providersSignal))
  }

  private val submitBus = new EventBus[Unit]()

  private val claimOpenVar = Var(false)
  private val claimCodeVar = Var("")
  private val claimBus     = new EventBus[Unit]()

  private val claimStream = {
    claimBus.events.map(_ => claimCodeVar.now().trim).filter(_.nonEmpty).flatMapSwitch(ApiClient.claimGuest)
  }

  // `flatMapSwitch` discards a superseded response, but the request has already been sent —
  // gating the stream on the in-flight flag is what actually prevents a double submit.
  private val submitStream = submitBus.events.filterWith(inFlightSignal.not)

  /** The other way in: a guest's transfer code, which is the only credential a guest account has. Kept below the
    * password form and folded away until it is asked for, since most visitors have an address and a password.
    */
  private def renderClaim(): HtmlElement = {
    div(
      cls := "mt-4 text-sm",
      child <-- claimOpenVar.signal.map { open =>
        if (!open) {
          a(
            cls        := "link link-hover",
            href       := "#",
            I18n.t(UiKeys.guestClaimLink),
            onClick.preventDefault.mapToUnit --> Observer[Unit](_ => claimOpenVar.set(true)),
          )
        } else {
          form(
            cls        := "flex flex-wrap items-end gap-2",
            noValidate := true,
            onSubmit.preventDefault.mapToUnit --> claimBus.writer,
            p(cls         := "basis-full opacity-70", I18n.t(UiKeys.guestClaimHint)),
            input(
              cls         := "input input-sm font-mono grow",
              placeholder := I18n.t(UiKeys.guestClaimPlaceholder),
              controlled(value <-- claimCodeVar.signal, onInput.mapToValue --> claimCodeVar.writer),
            ),
            button(cls    := "btn btn-sm", typ := "submit", I18n.t(UiKeys.guestClaimSubmit)),
          )
        }
      },
    )
  }

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
          ),
          div(
            cls  := "card-actions justify-end mt-4",
            button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, I18n.t(UiKeys.commonSignIn)),
          ),
          // Hidden entirely when no provider is configured, rather than leaving a stray divider
          // above nothing. Built once and shown or hidden, not rebuilt per signal change.
          child.maybe <-- hasProvidersSignal.map(Option.when(_)(socialBlock)),
          p(
            cls  := "text-sm mt-2",
            I18n.t(UiKeys.signInNoAccount),
            a(cls := "link", AppRouter.router.navigateTo(Page.SignUp), I18n.t(UiKeys.commonSignUp)),
          ),
        ),
      ),
      renderClaim(),
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
      claimStream -->
        Observer[Either[ApiError, AuthResponse]] {
          case Right(res) =>
            // Same arrangement as a password sign-in: writing the user into `AppState` is what moves the visitor off
            // this `RequireAnon` page, and the vocabulary is where a transfer code was going anyway.
            AppState.setUser(res.user)
            AppRouter.router.replaceState(Page.Words())
          case Left(err)  =>
            errorVar.set(Some(err.message))
        },
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
              noticeVar    -> Some(I18n.t(UiKeys.verificationResent)),
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
        I18n.t(UiKeys.verificationResendButton),
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
