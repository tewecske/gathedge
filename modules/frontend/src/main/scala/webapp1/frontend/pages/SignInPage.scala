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
  private val emailVar = Var("")
  private val emailSignal = emailVar.signal
  private val passwordVar = Var("")
  private val passwordSignal = passwordVar.signal

  /** Seeded from `?error=` so a failed OAuth round trip explains itself: the callback redirects here on failure, and
    * without this the user lands back on a blank form with no idea why.
    */
  private val errorVar: Var[Option[String]] = Var(OAuthMessages.queryParam("error").map(OAuthMessages.errorMessage))
  private val errorSignal = errorVar.signal
  private val inFlightVar = Var(false)
  private val inFlightSignal = inFlightVar.signal
  private val providersVar: Var[List[OAuthProvider]] = Var(Nil)
  private val providersSignal = providersVar.signal
  private val hasProvidersSignal = providersSignal.map(_.nonEmpty).distinct

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
          ),
          div(
            cls := "card-actions justify-end mt-4",
            button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Sign in"),
          ),
          // Hidden entirely when no provider is configured, rather than leaving a stray divider
          // above nothing. Built once and shown or hidden, not rebuilt per signal change.
          child.maybe <-- hasProvidersSignal.map(Option.when(_)(socialBlock)),
          p(
            cls := "text-sm mt-2",
            "No account? ",
            a(cls := "link", AppRouter.router.navigateTo(Page.SignUp), "Sign up"),
          ),
        ),
      ),
      ApiClient.providers -->
        Observer[Either[ApiError, ProvidersResponse]] {
          case Right(res) =>
            providersVar.set(res.providers)
          case Left(_) =>
            // The password form still works; offering no social buttons is the right degraded state.
            providersVar.set(Nil)
        },
      submitStream --> Observer[Unit](_ => Var.set(inFlightVar -> true, errorVar -> None)),
      submitStream.flatMapSwitch(_ => login()) -->
        Observer[Either[ApiError, AuthResponse]] {
          case Right(res) =>
            inFlightVar.set(false)
            AppState.setUser(res.user)
            AppRouter.router.pushState(Page.Home)
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
    )
  }

  private def login(): EventStream[Either[ApiError, AuthResponse]] = {
    ApiClient.login(LoginRequest(emailVar.now(), passwordVar.now()))
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
