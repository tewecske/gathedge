package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.components.{AppShell, ClaimCodeForm, OAuthButtons}
import gathedge.frontend.state.AppState
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.domain.{OAuthProvider, User}
import gathedge.shared.dto.{ProvidersResponse, SignupRequest}
import gathedge.frontend.i18n.I18n
import gathedge.shared.i18n.{MessageKeys, UiKeys}
import gathedge.shared.validation.Validation

object SignUpPage {

  /** Same signed-out shell as [[SignInPage]] — the two pages are one flow and must not differ in their chrome. */
  def render(): HtmlElement = AppShell.renderPublic(new SignUpPage().render())
}

private class SignUpPage {
  private val emailVar                               = Var("")
  private val emailSignal                            = emailVar.signal
  private val passwordVar                            = Var("")
  private val passwordSignal                         = passwordVar.signal
  private val errorVar: Var[Option[String]]          = Var(None)
  private val errorSignal                            = errorVar.signal
  private val inFlightVar                            = Var(false)
  private val inFlightSignal                         = inFlightVar.signal
  private val providersVar: Var[List[OAuthProvider]] = Var(Nil)
  private val providersSignal                        = providersVar.signal
  private val hasProvidersSignal                     = providersSignal.map(_.nonEmpty).distinct

  /** The same buttons as the sign-in form, and deliberately not labelled differently: the provider flow creates the
    * account when there is none and signs in when there is, so "sign up with" and "sign in with" are one button.
    */
  private lazy val socialBlock: HtmlElement = {
    div(div(cls := "divider text-xs", I18n.t(UiKeys.commonOr)), OAuthButtons.render(providersSignal))
  }

  private val submitBus = new EventBus[Unit]()

  /** Read once per render, not reactively: an in-place upgrade always ends in `AppState.setUser` clearing the guest
    * flag, which flips the `RequireAnon` guard and navigates this page away before a second submit could ever see the
    * flag change underneath it.
    */
  private val isGuestSignedIn: Boolean = AppState.currentUser.exists(_.isGuest)

  // Client-side validation happens in a pure `map`; the effects (error message, in-flight flag,
  // the request itself) all hang off the resulting stream as observers.
  private val validatedStream = submitBus.events.filterWith(inFlightSignal.not).map(_ => validate())

  def render(): HtmlElement = {
    div(
      cls := "w-full max-w-sm",
      // A real form element, so Enter in either field submits.
      form(
        cls := "card w-full bg-base-100 shadow-xl",
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        div(
          cls := "card-body",
          h1(
            cls := "card-title",
            I18n.t(
              if (isGuestSignedIn)
                UiKeys.guestUpgradeTitle
              else
                UiKeys.signUpTitle
            ),
          ),
          // The reassurance a plain signup does not need: this form reuses this browser's guest account rather than
          // creating an empty one, and the reader has words on it already.
          Option.when(isGuestSignedIn)(p(cls := "text-sm opacity-70", I18n.t(UiKeys.guestUpgradeHint))),
          child.maybe <-- errorSignal.map(_.map(renderError)),
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
            p(cls         := "label", I18n.t(UiKeys.commonPasswordHint, Validation.minPasswordLength)),
          ),
          div(
            cls  := "card-actions justify-end mt-4",
            button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, I18n.t(UiKeys.commonSignUp)),
          ),
          child.maybe <-- hasProvidersSignal.map(Option.when(_)(socialBlock)),
          Option.when(!isGuestSignedIn)(ClaimCodeForm.render()),
          p(
            cls  := "text-sm mt-2",
            I18n.t(UiKeys.signUpHaveAccount),
            a(cls := "link", AppRouter.router.navigateTo(Page.SignIn), I18n.t(UiKeys.commonSignIn)),
          ),
        ),
      ),
      ApiClient.providers -->
        Observer[Either[ApiError, ProvidersResponse]] {
          case Right(res) =>
            providersVar.set(res.providers)
          case Left(_)    =>
            providersVar.set(Nil)
        },
      validatedStream -->
        Observer[Either[String, SignupRequest]] {
          case Left(err) =>
            errorVar.set(Some(err))
          case Right(_)  =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      validatedStream
        .collect { case Right(request) =>
          request
        }
        // A guest upgrading is always signed in on success — there is no unverified-address wait, since the
        // account (and its session) already existed.
        .flatMapSwitch { request =>
          if (isGuestSignedIn)
            ApiClient.upgradeGuest(request.email, request.password).map(_.map(res => (res.user, true)))
          else
            ApiClient.signup(request).map(_.map(res => (res.user, res.signedIn)))
        } -->
        Observer[Either[ApiError, (User, Boolean)]] {
          // `signedIn` is false when the deployment requires a verified address: the account
          // exists but has no session, so there is nothing to put in AppState and nowhere to go
          // but the "check your inbox" page.
          case Right((_, false))   =>
            inFlightVar.set(false)
            AppRouter.router.pushState(Page.CheckInbox)
          case Right((user, true)) =>
            inFlightVar.set(false)
            // As in SignInPage: the `RequireAnon` guard navigates to Home off this write (guest or not — an
            // upgraded account is no longer a guest, so the same guard fires here too). Doing it here as well
            // would emit Home twice and remount the landing page.
            AppState.setUser(user)
          case Left(err)           =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
    )
  }

  private def validate(): Either[String, SignupRequest] = {
    val email    = emailVar.now()
    val password = passwordVar.now()
    // The message is worded here rather than carried on: the caller renders it directly, and the
    // whole point of `Validation` failing with a key is that both sides word it the same way.
    (for {
      validEmail    <- Validation.validateEmail(email)
      validPassword <- Validation.validatePassword(password)
    } yield SignupRequest(validEmail, validPassword)).left.map(I18n.resolve)
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
