package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{AdminSubmenu, Alert, AppShell, Pagination}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.{Paging, RouteUsage, SuspiciousUser}
import gathedge.shared.i18n.UiKeys

/** Which routes get used, and which accounts look unusual — the two things `usage_events` exists to answer (see
  * `gathedge.backend.service.UsageStatsService`).
  *
  * Not a listing like the audit trail: there is no per-row drill-down, so the window is a plain control on the page
  * rather than URL-carried state, the same choice `AdminSystemPage` makes for its own screen. Paging is the same choice
  * in miniature: `UsageStatsService.topRoutes`/`.suspiciousUsers` already answer the whole (small, bounded) result in
  * one response, so each card slices its own copy in the browser with the same `Pagination` control the server-paged
  * listings use, rather than the URL.
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

  /** Each card pages independently — a reader working through "most used" is not also moving through "least used". */
  private val defaultPageSize = 10

  private val routesMostPageVar     = Var(Paging.firstPage)
  private val routesMostPageSizeVar = Var(defaultPageSize)

  private val routesLeastPageVar     = Var(Paging.firstPage)
  private val routesLeastPageSizeVar = Var(defaultPageSize)

  private val suspiciousPageVar     = Var(Paging.firstPage)
  private val suspiciousPageSizeVar = Var(defaultPageSize)

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminUsageTitle)),
      AdminSubmenu.render(Page.AdminUsage),
      Alert.maybeError(errorVar.signal),
      renderWindowControl(),
      div(
        cls  := "grid gap-4 lg:grid-cols-2",
        child <-- routesVar.signal
          .map(_.map(_.sortBy(row => -row.count)))
          .map(routes => renderRoutesCard(UiKeys.adminUsageMostCard, routes, routesMostPageVar, routesMostPageSizeVar)),
        child <-- routesVar.signal
          .map(_.map(_.sortBy(_.count)))
          .map(routes =>
            renderRoutesCard(UiKeys.adminUsageLeastCard, routes, routesLeastPageVar, routesLeastPageSizeVar)
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
      // A new window replaces every card's rows, so a page number left over from the old ones would just as likely
      // point past the end of the new list.
      windowSignal -->
        Observer[Int](_ => {
          Var.set(
            routesMostPageVar  -> Paging.firstPage,
            routesLeastPageVar -> Paging.firstPage,
            suspiciousPageVar  -> Paging.firstPage,
          )
        }),
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

  /** The rows a page holds, one-based like everything else `Paging` touches. */
  private def pageSlice[A](rows: List[A], page: Int, pageSize: Int): List[A] = {
    val from = (Paging.boundedPage(Some(page)) - Paging.firstPage) * pageSize
    rows.slice(from, from + pageSize)
  }

  private def renderRoutesCard(
    titleKey: String,
    routes: Option[List[RouteUsage]],
    pageVar: Var[Int],
    pageSizeVar: Var[Int],
  ): HtmlElement = {
    card(
      I18n.t(titleKey),
      routes match {
        case None                       =>
          span(cls := "loading loading-spinner loading-sm")
        case Some(rows) if rows.isEmpty =>
          p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminUsageRoutesEmpty))
        case Some(rows)                 =>
          div(
            child <-- pageVar.signal
              .combineWith(pageSizeVar.signal)
              .map { case (page, pageSize) =>
                renderRoutesTable(rows, page, pageSize, pageVar, pageSizeVar)
              }
          )
      },
    )
  }

  private def renderRoutesTable(
    rows: List[RouteUsage],
    page: Int,
    pageSize: Int,
    pageVar: Var[Int],
    pageSizeVar: Var[Int],
  ): HtmlElement = {
    div(
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
            pageSlice(rows, page, pageSize).map(row => {
              tr(
                td(span(cls := "badge badge-ghost badge-sm font-mono", row.method)),
                td(cls := "font-mono text-xs", row.route),
                td(cls := "text-right", row.count.toString),
              )
            })
          ),
        ),
      ),
      Pagination.render(
        page = Val(page),
        total = Val(rows.size.toLong),
        pageSize = Val(pageSize),
        onPage = pageVar.writer,
        onPageSize = Observer[Int](size => Var.set(pageSizeVar -> size, pageVar -> Paging.firstPage)),
        summary = Val(I18n.plural(UiKeys.adminUsageRoutesCount, rows.size.toLong)),
      ),
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
              child <-- suspiciousPageVar.signal
                .combineWith(suspiciousPageSizeVar.signal)
                .map { case (page, pageSize) =>
                  renderSuspiciousTable(rows, page, pageSize)
                }
            )
        },
      ),
    )
  }

  private def renderSuspiciousTable(rows: List[SuspiciousUser], page: Int, pageSize: Int): HtmlElement = {
    div(
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
            pageSlice(rows, page, pageSize).map(user => {
              tr(
                cls := "hover",
                td(user.email.getOrElse(s"#${user.userId}")),
                td(cls := "text-right", user.eventCount.toString),
                td(cls := "text-right", user.distinctIpCount.toString),
              )
            })
          ),
        ),
      ),
      Pagination.render(
        page = Val(page),
        total = Val(rows.size.toLong),
        pageSize = Val(pageSize),
        onPage = suspiciousPageVar.writer,
        onPageSize =
          Observer[Int](size => Var.set(suspiciousPageSizeVar -> size, suspiciousPageVar -> Paging.firstPage)),
        summary = Val(I18n.plural(UiKeys.adminUsageSuspiciousCount, rows.size.toLong)),
      ),
    )
  }
}
