package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{AdminApiClient, ApiError}
import webapp1.frontend.components.{AdminSubmenu, Alert, AppShell, Formats, Labels, Pagination}
import webapp1.frontend.i18n.I18n
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.dto.{AuditAction, AuditEntry}
import webapp1.shared.i18n.UiKeys

/** The audit trail: every administrator action, most recent first.
  *
  * The `security` log has always carried these; this is the same events in a form a person can filter and page through
  * without shell access to the container.
  */
object AdminAuditPage {
  def render(): HtmlElement = AppShell.render(Page.AdminAudit, new AdminAuditPage().render())
}

private class AdminAuditPage {

  /** How many rows a page shows *and* how many one request asks for, which is what lets the numbered buttons line up
    * with what has been fetched: every fetch adds exactly one page at the end.
    *
    * Changing it does not refetch. The entries already held are re-sliced under the new size, and the next fetch simply
    * asks for that many — the cursor is the oldest row on hand either way, so a chunk of 20 followed by one of 100 is
    * as well-formed as any other sequence.
    */
  private val pageSizeVar    = Var(Pagination.defaultPageSize)
  private val pageSizeSignal = pageSizeVar.signal

  /** Every row fetched so far, oldest last. This is a *cursor*-paged listing, so the page numbers can only ever cover
    * what has been fetched; `hasMore` is what lets the next arrow reach past the last of them.
    */
  private val entriesVar    = Var(List.empty[AuditEntry])
  private val entriesSignal = entriesVar.signal

  private val pageVar    = Var(0)
  private val pageSignal = pageVar.signal

  private val pageCountSignal = {
    entriesSignal.combineWithFn(pageSizeSignal)((entries, size) => Pagination.pageCount(entries.size, size))
  }

  private val visibleSignal = {
    entriesSignal
      .combineWith(pageSignal, pageSizeSignal)
      .map((entries, page, size) => Pagination.slice(entries, page, size))
  }

  /** Empty means "every action". Held separately from the entries so changing it can restart the listing from the top
    * rather than paging into a differently-filtered set.
    */
  private val actionVar = Var("")
  private val actorVar  = Var("")

  private val errorVar: Var[Option[String]] = Var(None)
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  /** True once a request came back with fewer rows than it asked for: there is nothing older to fetch. */
  private val exhaustedVar = Var(false)

  /** Whether the next arrow may point one past the last numbered page. */
  private val hasMoreSignal = {
    entriesSignal.combineWithFn(exhaustedVar.signal)((entries, exhausted) => entries.nonEmpty && !exhausted).distinct
  }

  private val loadBus = new EventBus[Option[Long]]()

  private def blankToNone(value: String): Option[String] = Some(value.trim).filter(_.nonEmpty)

  private def request(limit: Int, before: Option[Long]): EventStream[Either[ApiError, List[AuditEntry]]] = {
    AdminApiClient.auditLog(
      limit = Some(limit),
      before = before,
      action = blankToNone(actionVar.now()),
      actorId = blankToNone(actorVar.now()).flatMap(_.toLongOption),
    )
  }

  /** A page the reader asked for. Anything already fetched is a re-slice; one past the end is a request for rows older
    * than the oldest on hand, and [[loadBus]]'s observer moves the page once they arrive.
    */
  private val pageRequests = {
    Observer[Int] { requested =>
      val entries = entriesVar.now()
      if (requested * pageSizeVar.now() < entries.size)
        pageVar.set(requested)
      else
        loadBus.emit(entries.lastOption.map(_.occurredAt))
    }
  }

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminAuditTitle)),
      AdminSubmenu.render(Page.AdminAudit),
      Alert.maybeError(errorVar.signal),
      renderFilters(),
      renderTable(),
      Pagination.render(
        page = pageSignal,
        pageCount = pageCountSignal,
        onPage = pageRequests,
        pageSize = pageSizeSignal,
        // No refetch: the rows on hand are re-sliced, and the next fetch asks for the new size.
        onPageSize = Observer[Int](size => Var.set(pageSizeVar -> size, pageVar -> 0)),
        hasMore = hasMoreSignal,
        busy = inFlightSignal,
      ),
      renderFooter(),
      loadBus.events --> Observer[Option[Long]](_ => Var.set(inFlightVar -> true, errorVar -> None)),
      // The limit is carried through with the answer rather than re-read from the Var: it is what decides whether the
      // listing is exhausted, and the reader may well have changed the page size while the request was out.
      loadBus.events.flatMapSwitch { before =>
        val limit = pageSizeVar.now()
        request(limit, before).map(result => (before, limit, result))
      } -->
        Observer[(Option[Long], Int, Either[ApiError, List[AuditEntry]])] {
          case (before, limit, Right(rows)) =>
            // `before` empty means this was a fresh listing, so it replaces and returns to the first page; otherwise it
            // is the next page down, which is the one the reader was asking to see.
            val entries = {
              if (before.isEmpty)
                rows
              else
                entriesVar.now() ++ rows
            }
            val page    = {
              if (before.isEmpty)
                0
              else
                Pagination.lastPage(entries.size, pageSizeVar.now())
            }
            Var.set(
              entriesVar   -> entries,
              pageVar      -> page,
              inFlightVar  -> false,
              exhaustedVar -> (rows.sizeIs < limit),
              errorVar     -> None,
            )
          case (_, _, Left(err))            =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(None)),
    )
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
            controlled(value <-- actionVar.signal, onChange.mapToValue --> actionVar.writer),
          ),
        ),
        label(
          cls := "form-control",
          span(cls      := "label-text text-xs", I18n.t(UiKeys.adminAuditActorId)),
          input(
            cls         := "input input-sm",
            typ         := "text",
            placeholder := I18n.t(UiKeys.adminAuditActorAny),
            controlled(value <-- actorVar.signal, onInput.mapToValue --> actorVar.writer),
          ),
        ),
        button(
          cls := "btn btn-sm btn-primary",
          typ := "button",
          disabled <-- inFlightSignal,
          I18n.t(UiKeys.commonApply),
          onClick.mapTo(None) --> loadBus.writer,
        ),
      ),
    )
  }

  private def renderTable(): HtmlElement = {
    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table table-sm",
        thead(
          tr(
            th(I18n.t(UiKeys.commonWhen)),
            th(I18n.t(UiKeys.adminAuditColActor)),
            th(I18n.t(UiKeys.adminAuditColAction)),
            th(I18n.t(UiKeys.adminAuditColTarget)),
            th(I18n.t(UiKeys.adminAuditColDetail)),
            th(I18n.t(UiKeys.commonFrom)),
          )
        ),
        tbody(
          children <--
            visibleSignal.splitSeq(_.id) { entrySignal =>
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

  /** The count of what has been fetched, which is not the same as the count of what exists — the wording distinguishes
    * the two, and the next arrow is now the only way to fetch more.
    */
  private def renderFooter(): HtmlElement = {
    div(
      cls := "mt-2 flex items-center gap-4",
      span(
        cls := "text-sm opacity-60",
        text <--
          entriesSignal
            .combineWith(exhaustedVar.signal)
            .map { (entries, exhausted) =>
              if (entries.isEmpty)
                I18n.t(UiKeys.adminAuditEmpty)
              else if (exhausted)
                I18n.plural(UiKeys.adminAuditCountAll, entries.size.toLong)
              else
                I18n.plural(UiKeys.adminAuditCountShown, entries.size.toLong)
            },
      ),
    )
  }
}
