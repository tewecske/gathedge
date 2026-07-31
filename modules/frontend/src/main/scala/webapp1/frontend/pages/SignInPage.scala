package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.ApiClient
import webapp1.frontend.state.AppState
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.dto.{AuthResponse, LoginRequest}

object SignInPage {
  def render(): HtmlElement = new SignInPage().render()
}

private class SignInPage {
  private val emailVar    = Var("")
  private val passwordVar = Var("")
  private val errorVar: Var[Option[String]] = Var(None)
  private val submitBus   = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
      div(
        cls := "card w-full max-w-sm bg-base-100 shadow-xl",
        div(
          cls := "card-body",
          h1(cls := "card-title", "Sign in"),
          child.maybe <-- errorVar.signal.map(_.map(renderError)),
          fieldSet(
            cls := "fieldset",
            legend(cls := "fieldset-legend", "Email"),
            input(
              cls := "input w-full",
              typ := "email",
              placeholder := "you@example.com",
              value <-- emailVar.signal,
              onInput.mapToValue --> emailVar.writer,
            ),
            legend(cls := "fieldset-legend", "Password"),
            input(
              cls := "input w-full",
              typ := "password",
              value <-- passwordVar.signal,
              onInput.mapToValue --> passwordVar.writer,
            ),
          ),
          div(
            cls := "card-actions justify-end mt-4",
            button(
              cls := "btn btn-primary",
              typ := "button",
              "Sign in",
              onClick.mapToUnit --> submitBus.writer,
            ),
          ),
          p(
            cls := "text-sm mt-2",
            "No account? ",
            a(cls := "link", AppRouter.router.navigateTo(Page.SignUp), "Sign up"),
          ),
        ),
      ),
      submitBus.events.flatMapSwitch(_ => login()) --> Observer[Either[String, Unit]] {
        case Right(_)    => errorVar.set(None)
        case Left(error) => errorVar.set(Some(error))
      },
    )
  }

  private def login() = {
    ApiClient.post[LoginRequest, AuthResponse]("/api/auth/login", LoginRequest(emailVar.now(), passwordVar.now())).map {
      case Right(res) =>
        AppState.setUser(res.user)
        AppRouter.router.pushState(Page.Home)
        Right(())
      case Left(err) => Left(err.message)
    }
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
