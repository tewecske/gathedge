package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.{AppRouter, Page}

/** Where a verification link lands.
  *
  * The link itself is a plain URL in an email, so it arrives as a full page load rather than a Waypoint navigation; the
  * token comes off the path and goes straight back to the server as a POST — which is what keeps it an ordinary
  * described endpoint carrying the CSRF header, instead of a second redirect-only route like the OAuth pair.
  *
  * On success this redirects to the sign-in form rather than granting a session: following a link out of an inbox
  * proves the address, not that whoever clicked it meant to sign in on this device.
  */
object VerifyEmailPage {
  def render(token: String): HtmlElement = new VerifyEmailPage(token).render()
}

private class VerifyEmailPage(token: String) {
  private val errorVar: Var[Option[String]]  = Var(None)
  private val resendEmailVar                 = Var("")
  private val resendBus                      = new EventBus[Unit]()
  private val noticeVar: Var[Option[String]] = Var(None)

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
      div(
        cls := "card w-full max-w-sm bg-base-100 shadow-xl",
        div(
          cls := "card-body",
          h1(cls := "card-title", "Verifying your email"),
          child <--
            errorVar.signal
              .map {
                case None          =>
                  span(cls := "loading loading-spinner loading-lg self-center")
                case Some(message) =>
                  div(role := "alert", cls := "alert alert-error", span(message))
              },
          child.maybe <-- errorVar.signal.map(_.map(_ => retryBlock)),
          child.maybe <-- noticeVar.signal.map(_.map(renderNotice)),
        ),
      ),
      ApiClient.verifyEmail(token) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            // A full navigation rather than pushState: the sign-in form seeds its notice from
            // `location.search`, which a Waypoint push would not repopulate.
            dom.window.location.href = "/sign-in?verified=1"
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      resendBus.events.flatMapSwitch(_ => ApiClient.resendVerification(resendEmailVar.now())) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(errorVar -> None, noticeVar -> Some("If that address needs verifying, a new link is on its way."))
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
    )
  }

  /** An expired or already-used link is the common failure, and the only useful answer to it is another link. */
  private lazy val retryBlock: HtmlElement = {
    form(
      onSubmit.preventDefault.mapToUnit --> resendBus.writer,
      fieldSet(
        cls   := "fieldset",
        legend(cls    := "fieldset-legend", "Email"),
        input(
          cls         := "input w-full",
          typ         := "email",
          placeholder := "you@example.com",
          controlled(value <-- resendEmailVar.signal, onInput.mapToValue --> resendEmailVar.writer),
        ),
      ),
      div(cls := "card-actions justify-end mt-4", button(cls := "btn btn-primary", typ := "submit", "Send a new link")),
      p(cls   := "text-sm mt-2", a(cls := "link", AppRouter.router.navigateTo(Page.SignIn), "Back to sign in")),
    )
  }

  private def renderNotice(message: String): HtmlElement = {
    div(role := "status", cls := "alert alert-success", span(message))
  }
}
