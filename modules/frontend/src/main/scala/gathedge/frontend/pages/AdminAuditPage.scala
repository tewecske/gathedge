package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{AdminSubmenu, Alert, AppShell, Formats, Labels, Pagination, SortHeader}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.AuditQuery
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.dto.{AuditAction, AuditEntry, AuditPage, AuditSort}
import gathedge.shared.i18n.UiKeys

/** The audit trail: every administrator action, most recent first.
  *
  * The `security` log has always carried these; this is the same events in a form a person can filter, order and page
  * through without shell access to the container.
  */
object AdminAuditPage {

  /** The listing state arrives from the URL and is written back to it — see `AdminUsersPage.render`, which takes the
    * same pair for the same reason.
    */
  def render(query: Signal[AuditQuery], onQuery: Observer[AuditQuery]): HtmlElement = {
    AppShell.render(Page.AdminAudit(), new AdminAuditPage(query, onQuery).render())
  }
}

private class AdminAuditPage(pageQuery: Signal[AuditQuery], onQuery: Observer[AuditQuery]) {

  /** `.distinct` because every reader here treats an emission as "ask the server again". */
  private val querySignal = pageQuery.distinct

  /** The page currently on screen, and how many entries match across every page. Both come off one response: this is
    * offset paging in SQL, so the server knows the total and the buttons below are counted off it.
    */
  private val entriesVar    = Var(List.empty[AuditEntry])
  private val entriesSignal = entriesVar.signal

  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct

  /** Every way this page asks for a different listing. The state lives in the URL, so a writer emits an edit rather
    * than updating a `Var` it owns; [[render]] applies it to whatever the address bar currently says.
    */
  private val changeBus = new EventBus[AuditQuery => AuditQuery]()

  private def change(edit: AuditQuery => AuditQuery): Unit = changeBus.emit(edit)

  /** What the filter inputs hold, which is not yet what has been asked for — the Apply button is what moves them into
    * the query. They follow the query in the other direction, so a bookmarked `?action=user.create` fills the controls
    * in, and so does the back button.
    */
  private val actionInputVar = Var("")
  private val actorInputVar  = Var("")

  private val errorVar: Var[Option[String]] = Var(None)
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val reloadBus = new EventBus[Unit]()
  private val applyBus  = new EventBus[Unit]()

  /** Every reason to call the endpoint, as one stream, so a reload cancels an in-flight request rather than racing it.
    */
  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  private def blankToNone(value: String): Option[String] = Some(value.trim).filter(_.nonEmpty)

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminAuditTitle)),
      AdminSubmenu.render(Page.AdminAudit()),
      Alert.maybeError(errorVar.signal),
      renderFilters(),
      renderTable(),
      Pagination.render(
        page = pageSignal,
        total = totalSignal,
        pageSize = pageSizeSignal,
        onPage = Observer[Int](page => change(_.copy(page = page))),
        onPageSize = Observer[Int](size => change(_.reset(_.copy(pageSize = size)))),
        summary = totalSignal.map(summaryOf).distinct,
        busy = inFlightSignal,
      ),
      // Each edit is applied to what the URL currently says, which is the only place the listing state lives.
      changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) } --> onQuery,
      querySignal.map(_.action.getOrElse("")).distinct --> actionInputVar.writer,
      querySignal.map(_.actorId.fold("")(_.toString)).distinct --> actorInputVar.writer,
      // Applying a filter is a query change like any other; if it happens to change nothing, `.distinct` swallows it
      // and the reload below is what still answers the button.
      applyBus.events -->
        Observer[Unit] { _ =>
          change {
            _.reset(
              _.copy(
                action = blankToNone(actionInputVar.now()),
                actorId = blankToNone(actorInputVar.now()).flatMap(_.toLongOption),
              )
            )
          }
          reloadBus.emit(())
        },
      listRequests --> Observer[AuditQuery](_ => Var.set(inFlightVar -> true, errorVar -> None)),
      listRequests.flatMapSwitch(load) -->
        Observer[Either[ApiError, AuditPage]] {
          case Right(result) =>
            Var.set(entriesVar -> result.items, totalVar -> result.total, inFlightVar -> false, errorVar -> None)
          case Left(err)     =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def load(query: AuditQuery): EventStream[Either[ApiError, AuditPage]] = {
    AdminApiClient.auditLog(
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      action = query.action,
      actorId = query.actorId,
    )
  }

  /** What the paging control says the trail holds — the count of what *matches*, which under a filter is not the count
    * of what exists.
    */
  private def summaryOf(total: Long): String = {
    if (total <= 0L)
      I18n.t(UiKeys.adminAuditEmpty)
    else
      I18n.plural(UiKeys.adminAuditCount, total)
  }

  private def renderFilters(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mb-4",
      div(
        cls := "card-body flex-row flex-wrap gap-2 items-end",
        label(
          cls := "form-control",
          span(cls := "label-text text-xs", I18n.t(UiKeys.adminAuditColAction)),
          select(
            cls    := "select select-sm",
            option(value := "", I18n.t(UiKeys.adminAuditEveryAction)),
            // The `value` stays the stored code — it is what the filter sends to the API.
            AuditAction.all.map(action => option(value := action, Labels.auditAction(action))),
            controlled(value <-- actionInputVar.signal, onChange.mapToValue --> actionInputVar.writer),
          ),
        ),
        label(
          cls := "form-control",
          span(cls      := "label-text text-xs", I18n.t(UiKeys.adminAuditActorId)),
          input(
            cls         := "input input-sm",
            typ         := "text",
            placeholder := I18n.t(UiKeys.adminAuditActorAny),
            controlled(value <-- actorInputVar.signal, onInput.mapToValue --> actorInputVar.writer),
          ),
        ),
        button(
          cls := "btn btn-sm btn-primary",
          typ := "button",
          disabled <-- inFlightSignal,
          I18n.t(UiKeys.commonApply),
          onClick.mapToUnit --> applyBus.writer,
        ),
      ),
    )
  }

  private def renderTable(): HtmlElement = {
    val onSort = Observer[SortHeader.Sort](sort => change(_.reset(_.copy(sort = sort))))

    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table table-sm",
        thead(
          tr(
            SortHeader.render(I18n.t(UiKeys.commonWhen), AuditSort.occurredAt, sortSignal, onSort),
            SortHeader.render(I18n.t(UiKeys.adminAuditColActor), AuditSort.actor, sortSignal, onSort),
            SortHeader.render(I18n.t(UiKeys.adminAuditColAction), AuditSort.action, sortSignal, onSort),
            // Target is two fields rendered as one and detail is prose; neither has an order anybody would ask for.
            th(I18n.t(UiKeys.adminAuditColTarget)),
            th(I18n.t(UiKeys.adminAuditColDetail)),
            SortHeader.render(I18n.t(UiKeys.commonFrom), AuditSort.ip, sortSignal, onSort),
          )
        ),
        tbody(
          children <--
            entriesSignal.splitSeq(_.id) { entrySignal =>
              renderRow(entrySignal.now())
            }
        ),
      ),
    )
  }

  private def renderRow(entry: AuditEntry): HtmlElement = {
    tr(
      cls := "hover",
      td(cls := "whitespace-nowrap", Formats.dateTime(entry.occurredAt)),
      // The address is a snapshot taken when the action happened, so it still names the administrator after their own
      // account is deleted — at which point there is no id left to link to either.
      td(entry.actorEmail.getOrElse(I18n.t(UiKeys.adminAuditSystemActor))),
      td(span(cls := "badge badge-ghost badge-sm", Labels.auditAction(entry.action))),
      td(renderTarget(entry)),
      td(entry.detail.getOrElse("")),
      td(cls := "font-mono text-xs", entry.ip.getOrElse(I18n.t(UiKeys.commonNone))),
    )
  }

  private def renderTarget(entry: AuditEntry): HtmlElement = {
    (entry.targetType, entry.targetId.flatMap(_.toLongOption)) match {
      case (Some("user"), Some(id)) =>
        a(
          cls := "link link-hover",
          AppRouter.router.navigateTo(Page.AdminUserDetail(id)),
          I18n.t(UiKeys.adminAuditTargetUser, id),
        )
      case _                        =>
        span(entry.targetType.getOrElse(I18n.t(UiKeys.commonNone)))
    }
  }
}
