package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{AdminSubmenu, Alert, AppShell}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.{RouteUsage, SuspiciousUser}
import gathedge.shared.i18n.UiKeys

/** Which routes get used, and which accounts look unusual — the two things `usage_events` exists to answer (see
  * `gathedge.backend.service.UsageStatsService`).
  *
  * Not a listing like the audit trail: there is no per-row drill-down, so the window is a plain control on the page
  * rather than URL-carried state, the same choice `AdminSystemPage` makes for its own screen.
  */
object AdminUsagePage {
  def render(): HtmlElement = AppShell.render(Page.AdminUsage, new AdminUsagePage().render())
}

private class AdminUsagePage {

  /** Hours the window control offers. `UiKeys.durationHours`'s plural is what labels each one, so a week and a month
    * read the same way an hour count does rather than needing their own key.
    */
  private val windowOptions: List[Int] = List(1, 24, 24 * 7, 24 * 30)
  private val defaultWindowHours: Int  = 24

  private val windowVar    = Var(defaultWindowHours)
  private val windowSignal = windowVar.signal.distinct

  private val routesVar: Var[Option[List[RouteUsage]]]         = Var(None)
  private val suspiciousVar: Var[Option[List[SuspiciousUser]]] = Var(None)

  private val errorVar: Var[Option[String]] = Var(None)

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminUsageTitle)),
      AdminSubmenu.render(Page.AdminUsage),
      Alert.maybeError(errorVar.signal),
      renderWindowControl(),
      div(
        cls  := "grid gap-4 lg:grid-cols-2",
        child <-- routesVar.signal.map(routes =>
          renderRoutesCard(UiKeys.adminUsageMostCard, routes.map(_.sortBy(row => -row.count)))
        ),
        child <-- routesVar.signal.map(routes =>
          renderRoutesCard(UiKeys.adminUsageLeastCard, routes.map(_.sortBy(_.count)))
        ),
      ),
      child <-- suspiciousVar.signal.map(renderSuspiciousCard),
      // A signal-to-observer binding fires with the current value as soon as it is mounted, so this both
      // loads the page on mount and reloads it whenever the window control changes — no separate bus.
      windowSignal.flatMapSwitch(hours => AdminApiClient.usageRoutes(Some(hours))) -->
        Observer[Either[ApiError, List[RouteUsage]]] {
          case Right(rows) =>
            Var.set(routesVar -> Some(rows), errorVar -> None)
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
      windowSignal.flatMapSwitch(hours => AdminApiClient.usageSuspicious(windowHours = Some(hours))) -->
        Observer[Either[ApiError, List[SuspiciousUser]]] {
          case Right(rows) =>
            Var.set(suspiciousVar -> Some(rows), errorVar -> None)
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
    )
  }

  private def windowLabel(hours: Int): String = I18n.plural(UiKeys.durationHours, hours.toLong)

  private def renderWindowControl(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mb-4",
      div(
        cls := "card-body flex-row flex-wrap gap-2 items-end",
        label(
          cls := "form-control",
          span(cls := "label-text text-xs", I18n.t(UiKeys.adminUsageWindowLabel)),
          select(
            cls    := "select select-sm",
            windowOptions.map(hours => option(value := hours.toString, windowLabel(hours))),
            controlled(
              value <-- windowSignal.map(_.toString),
              onChange.mapToValue.map(_.toIntOption.getOrElse(defaultWindowHours)) --> windowVar.writer,
            ),
          ),
        ),
      ),
    )
  }

  private def card(title: String, body: Modifier[HtmlElement]*): HtmlElement = {
    div(cls := "card bg-base-100 shadow", div(cls := "card-body", h2(cls := "card-title text-base", title), body))
  }

  private def renderRoutesCard(titleKey: String, routes: Option[List[RouteUsage]]): HtmlElement = {
    card(
      I18n.t(titleKey),
      routes match {
        case None                       =>
          span(cls := "loading loading-spinner loading-sm")
        case Some(rows) if rows.isEmpty =>
          p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminUsageRoutesEmpty))
        case Some(rows)                 =>
          div(
            cls := "overflow-x-auto",
            table(
              cls := "table table-sm",
              thead(
                tr(
                  th(I18n.t(UiKeys.adminUsageColMethod)),
                  th(I18n.t(UiKeys.adminUsageColRoute)),
                  th(cls := "text-right", I18n.t(UiKeys.adminUsageColCount)),
                )
              ),
              tbody(
                rows.map(row => {
                  tr(
                    td(span(cls := "badge badge-ghost badge-sm font-mono", row.method)),
                    td(cls := "font-mono text-xs", row.route),
                    td(cls := "text-right", row.count.toString),
                  )
                })
              ),
            ),
          )
      },
    )
  }

  private def renderSuspiciousCard(users: Option[List[SuspiciousUser]]): HtmlElement = {
    div(
      cls := "mt-4",
      card(
        I18n.t(UiKeys.adminUsageSuspiciousCard),
        p(cls := "text-sm opacity-60 mb-2", I18n.t(UiKeys.adminUsageSuspiciousHint)),
        users match {
          case None                       =>
            span(cls := "loading loading-spinner loading-sm")
          case Some(rows) if rows.isEmpty =>
            p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminUsageSuspiciousEmpty))
          case Some(rows)                 =>
            div(
              cls := "overflow-x-auto",
              table(
                cls := "table table-sm",
                thead(
                  tr(
                    th(I18n.t(UiKeys.adminUsageColUser)),
                    th(cls := "text-right", I18n.t(UiKeys.adminUsageColEvents)),
                    th(cls := "text-right", I18n.t(UiKeys.adminUsageColIps)),
                  )
                ),
                tbody(
                  rows.map(user => {
                    tr(
                      cls := "hover",
                      td(user.email.getOrElse(s"#${user.userId}")),
                      td(cls := "text-right", user.eventCount.toString),
                      td(cls := "text-right", user.distinctIpCount.toString),
                    )
                  })
                ),
              ),
            )
        },
      ),
    )
  }
}
