package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{AdminApiClient, ApiError}
import webapp1.frontend.components.{AdminSubmenu, Alert, AppShell, Formats, Labels}
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

  /** One request's worth. Paging appends rather than replaces, so this is also how much "Load older" fetches. */
  private val pageSize = 50

  private val entriesVar    = Var(List.empty[AuditEntry])
  private val entriesSignal = entriesVar.signal

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

  private val loadBus = new EventBus[Option[Long]]()

  private def blankToNone(value: String): Option[String] = Some(value.trim).filter(_.nonEmpty)

  private def request(before: Option[Long]): EventStream[Either[ApiError, List[AuditEntry]]] = {
    AdminApiClient.auditLog(
      limit = Some(pageSize),
      before = before,
      action = blankToNone(actionVar.now()),
      actorId = blankToNone(actorVar.now()).flatMap(_.toLongOption),
    )
  }

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminAuditTitle)),
      AdminSubmenu.render(Page.AdminAudit),
      Alert.maybeError(errorVar.signal),
      renderFilters(),
      renderTable(),
      renderFooter(),
      loadBus.events --> Observer[Option[Long]](_ => Var.set(inFlightVar -> true, errorVar -> None)),
      loadBus.events.flatMapSwitch { before =>
        request(before).map(result => (before, result))
      } -->
        Observer[(Option[Long], Either[ApiError, List[AuditEntry]])] {
          case (before, Right(rows)) =>
            // `before` empty means this was a fresh listing, so it replaces; otherwise it is the next page down.
            if (before.isEmpty)
              entriesVar.set(rows)
            else
              entriesVar.update(_ ++ rows)
            Var.set(inFlightVar -> false, exhaustedVar -> (rows.sizeIs < pageSize), errorVar -> None)
          case (_, Left(err))        =>
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

  private def renderFooter(): HtmlElement = {
    div(
      cls := "mt-4 flex items-center gap-4",
      child.maybe <--
        entriesSignal
          .combineWith(exhaustedVar.signal)
          .map { (entries, exhausted) =>
            Option.when(entries.nonEmpty && !exhausted) {
              button(
                cls := "btn btn-sm btn-outline",
                typ := "button",
                disabled <-- inFlightSignal,
                I18n.t(UiKeys.adminAuditLoadOlder),
                onClick.mapToUnit --> Observer[Unit](_ => loadBus.emit(entriesVar.now().lastOption.map(_.occurredAt))),
              )
            }
          },
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
