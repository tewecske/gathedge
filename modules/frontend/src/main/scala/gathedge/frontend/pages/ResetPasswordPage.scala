package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.i18n.{CurrentLocale, I18n}
import gathedge.shared.i18n.UiKeys
import gathedge.shared.validation.Validation

/** Where the link out of `AuthService.forgotPassword`'s email lands.
  *
  * Reached by a full page load out of an inbox, exactly like [[VerifyEmailPage]] — the token comes off the path and
  * goes straight back to the server, keeping it an ordinary described endpoint with the CSRF header rather than a
  * second redirect-only route.
  *
  * On success this redirects to the sign-in form rather than granting a session, for the same reason as
  * [[VerifyEmailPage]]: following the link proves the address, not that whoever clicked it meant to sign in on this
  * device — and `AuthService.resetPassword` has already revoked every session the account held.
  */
object ResetPasswordPage {
  def render(token: String): HtmlElement = new ResetPasswordPage(token).render()
}

private class ResetPasswordPage(token: String) {
  private val newPasswordVar                = Var("")
  private val errorVar: Var[Option[String]] = Var(None)
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal
  private val submitBus                     = new EventBus[Unit]()
  private val submitStream                  = submitBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
      form(
        cls := "card w-full max-w-sm bg-base-100 shadow-xl",
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        div(
          cls := "card-body",
          h1(cls := "card-title", I18n.t(UiKeys.resetPasswordTitle)),
          child.maybe <-- errorVar.signal.map(_.map(renderError)),
          fieldSet(
            cls  := "fieldset",
            legend(cls := "fieldset-legend", I18n.t(UiKeys.settingsNewPassword)),
            input(
              cls      := "input w-full",
              typ      := "password",
              controlled(value <-- newPasswordVar.signal, onInput.mapToValue --> newPasswordVar.writer),
            ),
            p(cls      := "label", I18n.t(UiKeys.commonPasswordHint, Validation.minPasswordLength.toString)),
          ),
          div(
            cls  := "card-actions justify-end mt-4",
            button(
              cls := "btn btn-primary",
              typ := "submit",
              disabled <-- inFlightSignal,
              I18n.t(UiKeys.resetPasswordSubmit),
            ),
          ),
        ),
      ),
      submitStream --> Observer[Unit](_ => Var.set(inFlightVar -> true, errorVar -> None)),
      submitStream.flatMapSwitch(_ => ApiClient.resetPassword(token, newPasswordVar.now())) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            // A full navigation rather than pushState: SignInPage seeds its notice from
            // `location.search`, which a Waypoint push would not repopulate — the same reasoning
            // VerifyEmailPage's redirect follows.
            dom.window.location.href = s"${CurrentLocale.prefix}/sign-in?reset=1"
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
    )
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
