package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{AdminSubmenu, Alert, AppShell, Formats}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.Page
import gathedge.shared.dto.RateLimitEntry
import gathedge.shared.i18n.UiKeys

/** Every `RateLimiter` key currently holding a failed attempt: who is blocked or approaching it, and for which action —
  * see `gathedge.backend.service.RateLimitKey` for the namespaces read here. A live snapshot plus a clear action, the
  * same shape `AdminWordFormsPage` is, one row at a time, plus a clear-everything button for the whole table
  * (`AdminSystemPage`'s maintenance card has one too; this is the same call, reached from the screen that lists what it
  * would clear).
  */
object AdminRateLimitsPage {
  def render(): HtmlElement = AppShell.render(Page.AdminRateLimits, new AdminRateLimitsPage().render())
}

private class AdminRateLimitsPage {

  private val entriesVar: Var[Option[List[RateLimitEntry]]] = Var(None)

  private val errorVar: Var[Option[String]] = Var(None)
  private val infoVar: Var[Option[String]]  = Var(None)
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val loadBus  = new EventBus[Unit]()
  private val clearBus = new EventBus[Option[String]]()

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminRateLimitsTitle)),
      AdminSubmenu.render(Page.AdminRateLimits),
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(infoVar.signal),
      p(cls  := "text-sm opacity-60 mb-4", I18n.t(UiKeys.adminRateLimitsHint)),
      div(
        cls  := "mb-4",
        button(
          cls := "btn btn-sm btn-error btn-outline",
          typ := "button",
          disabled <-- inFlightSignal,
          I18n.t(UiKeys.adminRateLimitsClearAll),
          onClick.mapToUnit -->
            Observer[Unit] { _ =>
              if (dom.window.confirm(I18n.t(UiKeys.adminRateLimitsClearAllConfirm)))
                clearBus.emit(None)
            },
        ),
      ),
      child <-- entriesVar.signal.map(renderTable),
      loadBus.events.flatMapSwitch(_ => AdminApiClient.rateLimits) -->
        Observer[Either[ApiError, List[RateLimitEntry]]] {
          case Right(entries) =>
            Var.set(entriesVar -> Some(entries), errorVar -> None)
          case Left(err)      =>
            errorVar.set(Some(err.message))
        },
      clearBus.events.filterWith(inFlightSignal.not) --> Observer[Option[String]](_ => started()),
      clearBus.events
        .filterWith(inFlightSignal.not)
        .flatMapSwitch(key => AdminApiClient.clearRateLimits(key)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(inFlightVar -> false, errorVar -> None, infoVar -> Some(I18n.t(UiKeys.adminRateLimitsCleared)))
            loadBus.emit(())
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message), infoVar -> None)
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def started(): Unit = {
    Var.set(inFlightVar -> true, errorVar -> None, infoVar -> None)
  }

  /** The namespace prefix before the first `:` names the action the key is metering; the rest is who it is metering —
    * an address, an account id, or a normalized email. `InMemoryRateLimiter.normalize` lowercases the whole key before
    * it is ever stored, so every prefix here is matched lowercase regardless of the mixed case `RateLimitKey`'s own
    * source uses (`groupJoin` stores as `groupjoin:...`).
    */
  private def scopeOf(key: String): (String, String) = {
    val idx = key.indexOf(':')
    if (idx < 0) {
      (I18n.t(UiKeys.adminRateLimitsScopeOther), key)
    } else {
      val prefix = key.substring(0, idx)
      val who    = key.substring(idx + 1)
      val label  = prefix match {
        case "email"       =>
          UiKeys.adminRateLimitsScopeEmail
        case "ip"          =>
          UiKeys.adminRateLimitsScopeIp
        case "verify"      =>
          UiKeys.adminRateLimitsScopeVerify
        case "signup"      =>
          UiKeys.adminRateLimitsScopeSignup
        case "pwreset"     =>
          UiKeys.adminRateLimitsScopePwReset
        case "guest"       =>
          UiKeys.adminRateLimitsScopeGuest
        case "claim"       =>
          UiKeys.adminRateLimitsScopeClaim
        case "groupjoin"   =>
          UiKeys.adminRateLimitsScopeGroupJoin
        case "shareredeem" =>
          UiKeys.adminRateLimitsScopeShareRedeem
        case "wordupload"  =>
          UiKeys.adminRateLimitsScopeWordUpload
        case _             =>
          UiKeys.adminRateLimitsScopeOther
      }
      (I18n.t(label), who)
    }
  }

  private def renderTable(entries: Option[List[RateLimitEntry]]): HtmlElement = {
    entries match {
      case None                       =>
        span(cls := "loading loading-spinner loading-sm")
      case Some(rows) if rows.isEmpty =>
        p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminRateLimitsEmpty))
      case Some(rows)                 =>
        div(
          cls := "overflow-x-auto",
          table(
            cls := "table table-sm",
            thead(
              tr(
                th(I18n.t(UiKeys.adminRateLimitsColScope)),
                th(I18n.t(UiKeys.adminRateLimitsColWho)),
                th(cls := "text-right", I18n.t(UiKeys.adminRateLimitsColAttempts)),
                th(I18n.t(UiKeys.adminRateLimitsColStatus)),
                th(I18n.t(UiKeys.adminRateLimitsColRetry)),
                th(I18n.t(UiKeys.adminRateLimitsColOldest)),
                th(),
              )
            ),
            // Already sorted by the backend: most attempts first.
            tbody(rows.map(renderRow)),
          ),
        )
    }
  }

  private def renderRow(entry: RateLimitEntry): HtmlElement = {
    val (scope, who) = scopeOf(entry.key)
    tr(
      cls := "hover",
      td(scope),
      td(cls := "font-mono text-xs break-all", who),
      td(cls := "text-right", entry.attempts.toString),
      td(
        if (entry.blocked)
          span(cls := "badge badge-error badge-sm", I18n.t(UiKeys.adminRateLimitsStatusBlocked))
        else
          span(cls := "badge badge-warning badge-sm", I18n.t(UiKeys.adminRateLimitsStatusWarn))
      ),
      td(
        if (entry.blocked)
          Formats.duration(entry.retryAfterMillis)
        else
          I18n.t(UiKeys.commonNone)
      ),
      td(Formats.dateTimeOpt(entry.oldestAttemptAt)),
      td(
        cls  := "text-right",
        button(
          cls := "btn btn-xs btn-error btn-outline",
          typ := "button",
          disabled <-- inFlightSignal,
          I18n.t(UiKeys.adminRateLimitsClear),
          onClick.mapToUnit -->
            Observer[Unit] { _ =>
              if (dom.window.confirm(I18n.t(UiKeys.adminRateLimitsClearConfirm)))
                clearBus.emit(Some(entry.key))
            },
        ),
      ),
    )
  }
}
