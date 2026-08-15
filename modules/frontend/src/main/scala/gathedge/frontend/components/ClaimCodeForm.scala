package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.dto.AuthResponse
import gathedge.shared.i18n.UiKeys

/** The other way in: a guest's transfer code, which is the only credential a guest account has. Shared by
  * [[gathedge.frontend.pages.SignInPage]] and [[gathedge.frontend.pages.SignUpPage]] — a visitor with a code in hand
  * may land on either, and both need the same way out of the address/password form.
  */
object ClaimCodeForm {
  def render(): HtmlElement = new ClaimCodeForm().render()
}

private class ClaimCodeForm {
  private val openVar                       = Var(false)
  private val codeVar                       = Var("")
  private val errorVar: Var[Option[String]] = Var(None)

  private val submitBus = new EventBus[Unit]()

  private val claimStream = {
    submitBus.events.map(_ => codeVar.now().trim).filter(_.nonEmpty).flatMapSwitch(ApiClient.claimGuest)
  }

  def render(): HtmlElement = {
    div(
      cls := "mt-4 text-sm",
      child.maybe <-- errorVar.signal.map(_.map(renderError)),
      child <-- openVar.signal.map { open =>
        if (!open) {
          a(
            cls  := "link link-hover",
            href := "#",
            I18n.t(UiKeys.guestClaimLink),
            onClick.preventDefault.mapToUnit --> Observer[Unit](_ => openVar.set(true)),
          )
        } else {
          form(
            cls        := "flex flex-wrap items-end gap-2",
            noValidate := true,
            onSubmit.preventDefault.mapToUnit --> submitBus.writer,
            p(cls         := "basis-full opacity-70", I18n.t(UiKeys.guestClaimHint)),
            input(
              cls         := "input input-sm font-mono grow",
              placeholder := I18n.t(UiKeys.guestClaimPlaceholder),
              controlled(value <-- codeVar.signal, onInput.mapToValue --> codeVar.writer),
            ),
            button(cls    := "btn btn-sm", typ := "submit", I18n.t(UiKeys.guestClaimSubmit)),
          )
        }
      },
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
    )
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error mt-2", span(message))
  }
}
