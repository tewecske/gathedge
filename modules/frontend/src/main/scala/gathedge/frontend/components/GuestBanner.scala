package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.dto.ClaimCodeResponse
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

/** What a guest is told, and the two ways out of being one.
  *
  * A guest account exists only in one browser's cookie jar: no address to recover it by, no password to sign in with.
  * That is the price of not asking anybody to register, and this is where it is paid back — a transfer code carries the
  * vocabulary to another machine, and upgrading turns the account into an ordinary one, in place.
  *
  * The code is the bearer credential for the account, and asking for it is idempotent: the server answers the same one
  * every time until the account is upgraded, so closing this panel does not lose it — asking again (from here or from
  * the account menu) gets it back.
  */
object GuestBanner {

  def render(): HtmlElement = new GuestBanner().render()
}

private class GuestBanner {

  private val codeVar    = Var(Option.empty[String])
  private val codeSignal = codeVar.signal

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)

  private val codeBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      cls := "card bg-base-200 mt-6",
      div(
        cls := "card-body gap-3",
        h2(cls := "card-title text-base", I18n.t(UiKeys.guestBannerTitle)),
        p(cls  := "text-sm opacity-70", I18n.t(UiKeys.guestBannerHint)),
        Alert.maybeError(errorVar.signal),
        Alert.maybeInfo(noticeVar.signal),
        div(
          cls  := "card-actions",
          button(
            cls := "btn btn-sm",
            typ := "button",
            I18n.t(UiKeys.guestGetCode),
            onClick.mapToUnit --> codeBus.writer,
          ),
          // The upgrade itself happens on Page.SignUp, guest-aware there: it reuses this account in place rather than
          // starting a new one, and the `RequireAnon` guard is what lets a guest reach that page at all.
          a(
            cls := "btn btn-sm btn-primary",
            AppRouter.router.navigateTo(Page.SignUp),
            I18n.t(UiKeys.guestUpgrade),
          ),
        ),
        child.maybe <-- codeSignal.map(_.map(renderCode)),
        codeBus.events.flatMapSwitch(_ => ApiClient.guestCode) -->
          Observer[Either[ApiError, ClaimCodeResponse]] {
            case Right(response) =>
              Var.set(codeVar -> Some(response.code), errorVar -> None)
            case Left(err)       =>
              errorVar.set(Some(err.message))
          },
      ),
    )
  }

  private def renderCode(transferCode: String): HtmlElement = {
    div(
      cls  := "alert alert-info flex flex-col items-start gap-2",
      role := "status",
      p(cls := "text-sm", I18n.t(UiKeys.guestCodeOnce)),
      div(
        cls := "flex flex-wrap items-center gap-2",
        code(cls := "font-mono text-lg tracking-wider whitespace-nowrap", transferCode),
        button(
          cls    := "btn btn-xs",
          typ    := "button",
          I18n.t(UiKeys.guestCodeCopy),
          onClick.mapToUnit --> Observer[Unit](_ => copyToClipboard(transferCode)),
        ),
        button(
          cls    := "btn btn-xs btn-ghost",
          typ    := "button",
          I18n.t(UiKeys.guestCodeClose),
          onClick.mapToUnit --> Observer[Unit](_ => codeVar.set(None)),
        ),
      ),
    )
  }

  /** Feature-checked, like `Popover.hide`: the clipboard API is absent in jsdom and on older browsers, and a copy
    * button that throws would take the page with it. The code is on screen either way.
    */
  private def copyToClipboard(value: String): Unit = {
    try {
      val clipboard = dom.window.navigator.asInstanceOf[scala.scalajs.js.Dynamic].clipboard
      if (!scala.scalajs.js.isUndefined(clipboard)) {
        clipboard.writeText(value)
        noticeVar.set(Some(I18n.t(UiKeys.guestCodeCopied)))
      }
    } catch { case _: Throwable => () }
  }
}
