package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.{AppRouter, Page}

/** Where signup lands when the deployment requires a verified address: the account exists, but there is no session yet.
  *
  * The address is typed in again rather than carried over from the signup form, because this page is also reachable
  * directly (and after a reload) — and because the resend endpoint answers identically for every address, so nothing is
  * given away by asking.
  */
object CheckInboxPage {
  def render(): HtmlElement = new CheckInboxPage().render()
}

private class CheckInboxPage {
  private val emailVar = Var("")
  private val errorVar: Var[Option[String]] = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)
  private val inFlightVar = Var(false)
  private val inFlightSignal = inFlightVar.signal
  private val submitBus = new EventBus[Unit]()
  private val submitStream = submitBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
      form(
        cls := "card w-full max-w-sm bg-base-100 shadow-xl",
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        div(
          cls := "card-body",
          h1(cls := "card-title", "Check your inbox"),
          p(
            cls := "text-sm",
            "We sent you a link to confirm your email address. Follow it to finish setting up your account.",
          ),
          child.maybe <-- noticeVar.signal.map(_.map(renderNotice)),
          child.maybe <-- errorVar.signal.map(_.map(renderError)),
          fieldSet(
            cls := "fieldset",
            legend(cls := "fieldset-legend", "Didn't get it? Enter your email to resend"),
            input(
              cls := "input w-full",
              typ := "email",
              placeholder := "you@example.com",
              controlled(value <-- emailVar.signal, onInput.mapToValue --> emailVar.writer),
            ),
          ),
          div(
            cls := "card-actions justify-end mt-4",
            button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Resend link"),
          ),
          p(
            cls := "text-sm mt-2",
            "Already confirmed? ",
            a(cls := "link", AppRouter.router.navigateTo(Page.SignIn), "Sign in"),
          ),
        ),
      ),
      submitStream --> Observer[Unit](_ => Var.set(inFlightVar -> true, errorVar -> None, noticeVar -> None)),
      submitStream.flatMapSwitch(_ => ApiClient.resendVerification(emailVar.now())) -->
        Observer[Either[ApiError, Unit]] {
          // Deliberately non-committal: the server answers the same for an unknown address, an
          // already-verified one and a fresh send, and this copy must not say more than that.
          case Right(_) =>
            Var.set(
              inFlightVar -> false,
              noticeVar -> Some("If that address needs verifying, a new link is on its way."),
            )
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
    )
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }

  private def renderNotice(message: String): HtmlElement = {
    div(role := "status", cls := "alert alert-success", span(message))
  }
}
