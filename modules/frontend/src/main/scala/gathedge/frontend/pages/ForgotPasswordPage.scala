package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.components.AppShell
import gathedge.frontend.i18n.I18n
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.i18n.{MessageKeys, UiKeys}

/** Where "Forgot your password?" on [[SignInPage]] leads: an email address in, a password-reset link out, always
  * answered the same way — see [[gathedge.frontend.api.ApiClient.forgotPassword]] for why the endpoint cannot say more
  * than "sent".
  */
object ForgotPasswordPage {
  def render(): HtmlElement = AppShell.renderPublic(new ForgotPasswordPage().render())
}

private class ForgotPasswordPage {
  private val emailVar                       = Var("")
  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)
  private val inFlightVar                    = Var(false)
  private val inFlightSignal                 = inFlightVar.signal
  private val submitBus                      = new EventBus[Unit]()
  private val submitStream                   = submitBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      cls := "w-full max-w-sm",
      form(
        cls := "card w-full bg-base-100 shadow-xl",
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        div(
          cls := "card-body",
          h1(cls := "card-title", I18n.t(UiKeys.forgotPasswordTitle)),
          p(cls  := "text-sm", I18n.t(UiKeys.forgotPasswordBody)),
          child.maybe <-- noticeVar.signal.map(_.map(renderNotice)),
          child.maybe <-- errorVar.signal.map(_.map(renderError)),
          fieldSet(
            cls  := "fieldset",
            legend(cls    := "fieldset-legend", I18n.t(MessageKeys.fieldEmail)),
            input(
              cls         := "input w-full",
              typ         := "email",
              placeholder := "you@example.com",
              controlled(value <-- emailVar.signal, onInput.mapToValue --> emailVar.writer),
            ),
          ),
          div(
            cls  := "card-actions justify-end mt-4",
            button(
              cls := "btn btn-primary",
              typ := "submit",
              disabled <-- inFlightSignal,
              I18n.t(UiKeys.forgotPasswordSubmit),
            ),
          ),
          p(
            cls  := "text-sm mt-2",
            a(cls := "link", AppRouter.router.navigateTo(Page.SignIn), I18n.t(UiKeys.verifyBackToSignIn)),
          ),
        ),
      ),
      submitStream --> Observer[Unit](_ => Var.set(inFlightVar -> true, errorVar -> None, noticeVar -> None)),
      submitStream.flatMapSwitch(_ => ApiClient.forgotPassword(emailVar.now())) -->
        Observer[Either[ApiError, Unit]] {
          // Deliberately non-committal: the server answers the same for an unknown address and a
          // known one, and this copy must not say more than that.
          case Right(_)  =>
            Var.set(inFlightVar -> false, noticeVar -> Some(I18n.t(UiKeys.forgotPasswordSent)))
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
