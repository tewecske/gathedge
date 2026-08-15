package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.dto.{AuthResponse, ClaimCodeResponse}
import gathedge.shared.i18n.{MessageKeys, UiKeys}
import gathedge.shared.validation.Validation
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

  private val upgradingVar = Var(false)

  private val emailVar    = Var("")
  private val passwordVar = Var("")

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)

  private val inFlightVar    = Var(false)
  private val inFlightSignal = inFlightVar.signal

  private val codeBus    = new EventBus[Unit]()
  private val upgradeBus = new EventBus[Unit]()

  /** Validation runs before anything is sent, and produces the same messages the server would: both sides call
    * `shared`'s `Validation`.
    */
  private def validate(): Either[String, (String, String)] = {
    for {
      email    <- Validation.validateEmail(emailVar.now()).left.map(I18n.resolve)
      password <- Validation.validatePassword(passwordVar.now()).left.map(I18n.resolve)
    } yield (email, password)
  }

  private val upgradeStream = upgradeBus.events.filterWith(inFlightSignal.not).map(_ => validate())

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
            disabled <-- inFlightSignal,
            I18n.t(UiKeys.guestGetCode),
            onClick.mapToUnit --> codeBus.writer,
          ),
          button(
            cls := "btn btn-sm btn-primary",
            typ := "button",
            I18n.t(UiKeys.guestUpgrade),
            onClick.mapToUnit --> Observer[Unit](_ => upgradingVar.update(!_)),
          ),
        ),
        child.maybe <-- codeSignal.map(_.map(renderCode)),
        child.maybe <-- upgradingVar.signal.map(Option.when(_)(renderUpgradeForm())),
        codeBus.events.filterWith(inFlightSignal.not).flatMapSwitch(_ => ApiClient.guestCode) -->
          Observer[Either[ApiError, ClaimCodeResponse]] {
            case Right(response) =>
              Var.set(codeVar -> Some(response.code), errorVar -> None)
            case Left(err)       =>
              errorVar.set(Some(err.message))
          },
        upgradeStream --> Observer[Either[String, (String, String)]] {
          case Left(message) =>
            errorVar.set(Some(message))
          case Right(_)      =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
        upgradeStream
          .collect { case Right(credentials) => credentials }
          .flatMapSwitch { case (email, password) => ApiClient.upgradeGuest(email, password) } -->
          Observer[Either[ApiError, AuthResponse]] {
            case Right(response) =>
              // The account is no longer a guest, so the banner unmounts itself: `AppState` is what the page reads to
              // decide whether to render it at all.
              AppState.setUser(response.user)
              Var.set(inFlightVar -> false, upgradingVar -> false, noticeVar -> Some(I18n.t(UiKeys.guestUpgradeDone)))
            case Left(err)       =>
              Var.set(inFlightVar -> false, errorVar -> Some(err.message))
          },
      ),
    )
  }

  private def renderCode(transferCode: String): HtmlElement = {
    div(
      cls  := "alert alert-info flex-col items-start gap-2",
      role := "status",
      p(cls := "text-sm", I18n.t(UiKeys.guestCodeOnce)),
      div(
        cls := "flex flex-wrap items-center gap-2",
        code(cls := "font-mono text-lg tracking-wider", transferCode),
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

  private def renderUpgradeForm(): HtmlElement = {
    form(
      cls        := "flex flex-wrap items-end gap-2",
      noValidate := true,
      onSubmit.preventDefault.mapToUnit --> upgradeBus.writer,
      p(cls         := "basis-full text-sm opacity-70", I18n.t(UiKeys.guestUpgradeHint)),
      input(
        cls         := "input input-sm",
        typ         := "email",
        placeholder := I18n.t(MessageKeys.fieldEmail),
        controlled(value <-- emailVar.signal, onInput.mapToValue --> emailVar.writer),
      ),
      input(
        cls         := "input input-sm",
        typ         := "password",
        placeholder := I18n.t(MessageKeys.fieldPassword),
        controlled(value <-- passwordVar.signal, onInput.mapToValue --> passwordVar.writer),
      ),
      button(
        cls         := "btn btn-sm btn-primary",
        typ         := "submit",
        disabled <-- inFlightSignal,
        I18n.t(UiKeys.guestUpgradeTitle),
      ),
    )
  }
}
