package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.i18n.{CurrentLocale, I18n}
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.i18n.UiKeys

/** Where an email-change confirmation link lands.
  *
  * The link is a plain URL in an email, so — like [[VerifyEmailPage]] — it arrives as a full page load rather than a
  * Waypoint navigation, and the token goes straight back to the server as a POST.
  *
  * Unlike [[VerifyEmailPage]], a dead link has nothing to offer a retry with: the pending change lives on the account,
  * not on this page, so the only useful thing a failure can do is point back to Settings, where a fresh request can be
  * made. A success is a full navigation there too, whether or not this browser still holds the session that requested
  * it — `Page.Settings`'s own `RequireAuth` guard sends a signed-out reader on to sign in.
  */
object ConfirmEmailChangePage {
  def render(token: String): HtmlElement = new ConfirmEmailChangePage(token).render()
}

private class ConfirmEmailChangePage(token: String) {
  private val errorVar: Var[Option[String]] = Var(None)

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
      div(
        cls := "card w-full max-w-sm bg-base-100 shadow-xl",
        div(
          cls := "card-body",
          h1(cls := "card-title", I18n.t(UiKeys.confirmEmailChangeTitle)),
          child <--
            errorVar.signal
              .map {
                case None          =>
                  span(cls := "loading loading-spinner loading-lg self-center")
                case Some(message) =>
                  div(role := "alert", cls := "alert alert-error", span(message))
              },
          child.maybe <--
            errorVar.signal.map(
              _.map(_ => {
                p(
                  cls := "text-sm mt-2",
                  a(
                    cls := "link",
                    AppRouter.router.navigateTo(Page.Settings),
                    I18n.t(UiKeys.confirmEmailChangeBackToSettings),
                  ),
                )
              })
            ),
        ),
      ),
      ApiClient.confirmEmailChange(token) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            // A full navigation, not pushState: the settings page reads `?emailChanged=1` from
            // `location.search`, the same reason `VerifyEmailPage` navigates rather than pushes.
            dom.window.location.href = s"${CurrentLocale.prefix}/settings?emailChanged=1"
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
    )
  }
}
