package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.OAuthButtons
import webapp1.frontend.state.AppState
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.OAuthProvider
import webapp1.shared.dto.{ProvidersResponse, SignupRequest, SignupResponse}
import webapp1.shared.validation.Validation

object SignUpPage {
  def render(): HtmlElement = new SignUpPage().render()
}

private class SignUpPage {
  private val emailVar = Var("")
  private val emailSignal = emailVar.signal
  private val passwordVar = Var("")
  private val passwordSignal = passwordVar.signal
  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal = errorVar.signal
  private val inFlightVar = Var(false)
  private val inFlightSignal = inFlightVar.signal
  private val providersVar: Var[List[OAuthProvider]] = Var(Nil)
  private val providersSignal = providersVar.signal
  private val hasProvidersSignal = providersSignal.map(_.nonEmpty).distinct

  /** The same buttons as the sign-in form, and deliberately not labelled differently: the provider flow creates the
    * account when there is none and signs in when there is, so "sign up with" and "sign in with" are one button.
    */
  private lazy val socialBlock: HtmlElement = {
    div(div(cls := "divider text-xs", "or"), OAuthButtons.render(providersSignal))
  }

  private val submitBus = new EventBus[Unit]()

  // Client-side validation happens in a pure `map`; the effects (error message, in-flight flag,
  // the request itself) all hang off the resulting stream as observers.
  private val validatedStream = submitBus.events.filterWith(inFlightSignal.not).map(_ => validate())

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
      // A real form element, so Enter in either field submits.
      form(
        cls := "card w-full max-w-sm bg-base-100 shadow-xl",
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        div(
          cls := "card-body",
          h1(cls := "card-title", "Create account"),
          child.maybe <-- errorSignal.map(_.map(renderError)),
          fieldSet(
            cls := "fieldset",
            legend(cls := "fieldset-legend", "Email"),
            input(
              cls := "input w-full",
              typ := "email",
              placeholder := "you@example.com",
              controlled(value <-- emailSignal, onInput.mapToValue --> emailVar.writer),
            ),
            legend(cls := "fieldset-legend", "Password"),
            input(
              cls := "input w-full",
              typ := "password",
              controlled(value <-- passwordSignal, onInput.mapToValue --> passwordVar.writer),
            ),
            p(cls := "label", s"At least ${Validation.minPasswordLength} characters"),
          ),
          div(
            cls := "card-actions justify-end mt-4",
            button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Sign up"),
          ),
          child.maybe <-- hasProvidersSignal.map(Option.when(_)(socialBlock)),
          p(
            cls := "text-sm mt-2",
            "Already have an account? ",
            a(cls := "link", AppRouter.router.navigateTo(Page.SignIn), "Sign in"),
          ),
        ),
      ),
      ApiClient.providers -->
        Observer[Either[ApiError, ProvidersResponse]] {
          case Right(res) =>
            providersVar.set(res.providers)
          case Left(_) =>
            providersVar.set(Nil)
        },
      validatedStream -->
        Observer[Either[String, SignupRequest]] {
          case Left(err) =>
            errorVar.set(Some(err))
          case Right(_) =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      validatedStream
        .collect { case Right(request) =>
          request
        }
        .flatMapSwitch(request => ApiClient.signup(request)) -->
        Observer[Either[ApiError, SignupResponse]] {
          // `signedIn` is false when the deployment requires a verified address: the account
          // exists but has no session, so there is nothing to put in AppState and nowhere to go
          // but the "check your inbox" page.
          case Right(res) if !res.signedIn =>
            inFlightVar.set(false)
            AppRouter.router.pushState(Page.CheckInbox)
          case Right(res) =>
            inFlightVar.set(false)
            // As in SignInPage: the `RequireAnon` guard navigates to Home off this write. Doing it
            // here too would emit Home twice and remount the landing page.
            AppState.setUser(res.user)
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
    )
  }

  private def validate(): Either[String, SignupRequest] = {
    val email = emailVar.now()
    val password = passwordVar.now()
    for {
      validEmail <- Validation.validateEmail(email)
      validPassword <- Validation.validatePassword(password)
    } yield SignupRequest(validEmail, validPassword)
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
