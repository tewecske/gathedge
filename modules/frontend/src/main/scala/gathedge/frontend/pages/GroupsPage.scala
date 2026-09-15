package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GroupApiClient}
import gathedge.frontend.components.{Alert, AppShell, HelpIcon, Pagination, SortHeader}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.GroupQuery
import gathedge.shared.domain.{Group, GroupRole}
import gathedge.shared.dto.{GroupDetail, GroupPage, GroupSort}
import gathedge.shared.i18n.UiKeys

/** Browsing every group, creating one (the caller becomes its sole admin), and joining one by invite code. Paged,
  * sorted and filtered by the server the same way `AdminUsersPage` is — see `AppRouter.Page.Groups` and
  * [[gathedge.frontend.listing.GroupQuery]]. See `GroupDetailPage` for one group's roster/invite-code/tag-list detail.
  */
object GroupsPage {

  /** The listing state arrives from the URL and is written back to it, so this page holds none of it — see
    * `AdminUsersPage.render`'s own doc comment for why.
    */
  def render(query: Signal[GroupQuery], onQuery: Observer[GroupQuery]): HtmlElement = {
    AppShell.render(Page.Groups(), new GroupsPage(query, onQuery).render())
  }
}

private class GroupsPage(pageQuery: Signal[GroupQuery], onQuery: Observer[GroupQuery]) {

  /** `.distinct` because every reader here treats an emission as "ask the server again". */
  private val querySignal = pageQuery.distinct

  private val groupsVar    = Var(List.empty[Group])
  private val groupsSignal = groupsVar.signal

  /** How many groups match the current filters, across every page — the server counts it. */
  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct

  /** Every way this page asks for a different listing, as one stream of edits — the same shape `AdminUsersPage`'s own
    * `changeBus` follows, for the same reason: the state lives in the URL, not in a `Var` this page owns.
    */
  private val changeBus = new EventBus[GroupQuery => GroupQuery]()

  private def change(edit: GroupQuery => GroupQuery): Unit = changeBus.emit(edit)

  // The two debounced filter boxes, each following the query in one direction and reporting what was typed in the
  // other — see `AdminUsersPage.searchInputVar`'s doc comment for why neither reads itself back.
  private val searchInputVar = Var("")
  private val searchTypedBus = new EventBus[String]()

  private val tagInputVar = Var("")
  private val tagTypedBus = new EventBus[String]()

  private val searchDebounceMs = 300

  private val nameInputVar = Var("")
  private val codeInputVar = Var("")

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)
  private val creatingVar                    = Var(false)
  private val joiningVar                     = Var(false)

  /** Whether the listing is being fetched. Only the paging control reads it, to grey its buttons out between pages. */
  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  private val reloadBus = new EventBus[Unit]()
  private val createBus = new EventBus[Unit]()
  private val joinBus   = new EventBus[Unit]()

  private val createStream = createBus.events.filterWith(creatingVar.signal.not).sample(nameInputVar.signal)
  private val joinStream   = joinBus.events.filterWith(joiningVar.signal.not).sample(codeInputVar.signal)

  /** Every reason to call the list endpoint: the query changed, or somebody asked for it again (after create/join). */
  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  def render(): HtmlElement = {
    div(
      cls := "max-w-4xl mx-auto",
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(noticeVar.signal),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          h1(
            cls   := "card-title text-2xl flex items-center gap-1",
            span(I18n.t(UiKeys.groupsTitle)),
            HelpIcon.render(I18n.t(UiKeys.helpGroups)),
          ),
          renderForms(),
          div(cls := "divider"),
          renderFilters(),
          renderList(),
          Pagination.render(
            page = pageSignal,
            total = totalSignal,
            pageSize = pageSizeSignal,
            onPage = Observer[Int](page => change(_.copy(page = page))),
            onPageSize = Observer[Int](size => change(_.reset(_.copy(pageSize = size)))),
            summary = totalSignal.map(summaryOf).distinct,
            busy = loadingSignal,
          ),
        ),
      ),
      // Each edit is applied to what the URL currently says, which is the only place the listing state lives.
      changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) } --> onQuery,
      querySignal.map(_.search).distinct --> searchInputVar.writer,
      querySignal.map(_.tag).distinct --> tagInputVar.writer,
      searchTypedBus.events.debounce(searchDebounceMs).withCurrentValueOf(querySignal) -->
        Observer[(String, GroupQuery)] { case (typed, current) =>
          val wanted = typed.trim
          if (wanted != current.search) {
            change(_.reset(_.copy(search = wanted)))
          }
        },
      tagTypedBus.events.debounce(searchDebounceMs).withCurrentValueOf(querySignal) -->
        Observer[(String, GroupQuery)] { case (typed, current) =>
          val wanted = typed.trim
          if (wanted != current.tag) {
            change(_.reset(_.copy(tag = wanted)))
          }
        },
      listRequests --> Observer[GroupQuery](_ => Var.set(loadingVar -> true, errorVar -> None)),
      listRequests.flatMapSwitch(load) -->
        Observer[Either[ApiError, GroupPage]] {
          case Right(result) =>
            Var.set(groupsVar -> result.items, totalVar -> result.total, loadingVar -> false, errorVar -> None)
          case Left(err)     =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message))
        },
      createStream --> Observer[String](_ => Var.set(creatingVar -> true, errorVar -> None, noticeVar -> None)),
      createStream.flatMapSwitch(name => GroupApiClient.create(name)) -->
        Observer[Either[ApiError, GroupDetail]] {
          case Right(created) =>
            Var.set(creatingVar -> false, nameInputVar -> "")
            AppRouter.router.pushState(Page.GroupDetail(created.id))
          case Left(err)      =>
            Var.set(creatingVar -> false, errorVar -> Some(err.message))
        },
      joinStream --> Observer[String](_ => Var.set(joiningVar -> true, errorVar -> None, noticeVar -> None)),
      joinStream.flatMapSwitch(code => GroupApiClient.join(code)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(joiningVar -> false, codeInputVar -> "", noticeVar -> Some(I18n.t(UiKeys.groupsJoinSuccess)))
            reloadBus.emit(())
          case Left(err) =>
            Var.set(joiningVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def load(query: GroupQuery): EventStream[Either[ApiError, GroupPage]] = {
    GroupApiClient.listPage(
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      search = Option(query.search).filter(_.nonEmpty),
      tag = Option(query.tag).filter(_.nonEmpty),
    )
  }

  /** What the paging control says the listing holds. Zero is worded as its own sentence rather than "0 study groups",
    * the same rule `AdminUsersPage.summaryOf` follows.
    */
  private def summaryOf(total: Long): String = {
    if (total <= 0L)
      I18n.t(UiKeys.groupsNoMatches)
    else
      I18n.plural(UiKeys.groupsCount, total)
  }

  private def renderForms(): HtmlElement = {
    div(
      cls := "flex flex-col sm:flex-row gap-4",
      form(
        cls := "flex-1",
        onSubmit.preventDefault.mapToUnit --> createBus.writer,
        fieldSet(
          cls := "fieldset",
          label(cls := "fieldset-legend", I18n.t(UiKeys.groupsCreateLabel)),
          div(
            cls     := "flex gap-2",
            input(
              cls         := "input flex-1",
              placeholder := I18n.t(UiKeys.groupsCreatePlaceholder),
              controlled(value <-- nameInputVar.signal, onInput.mapToValue --> nameInputVar.writer),
            ),
            button(
              cls         := "btn btn-primary",
              typ         := "submit",
              disabled <-- creatingVar.signal,
              I18n.t(UiKeys.groupsCreateButton),
            ),
          ),
        ),
      ),
      form(
        cls := "flex-1",
        onSubmit.preventDefault.mapToUnit --> joinBus.writer,
        fieldSet(
          cls := "fieldset",
          label(cls := "fieldset-legend", I18n.t(UiKeys.groupsJoinLabel)),
          div(
            cls     := "flex gap-2",
            input(
              cls         := "input flex-1",
              placeholder := I18n.t(UiKeys.groupsJoinPlaceholder),
              controlled(value <-- codeInputVar.signal, onInput.mapToValue --> codeInputVar.writer),
            ),
            button(
              cls         := "btn btn-primary",
              typ         := "submit",
              disabled <-- joiningVar.signal,
              I18n.t(UiKeys.groupsJoinButton),
            ),
          ),
        ),
      ),
    )
  }

  private def renderFilters(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-2 mb-4",
      label(
        cls := "form-control",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.groupsFilterNameLabel)),
        input(
          // `search` rather than `text`, so the browser offers its own clear button on the platforms that have one —
          // the same reason `AdminUsersPage.renderSearch` picks it.
          cls         := "input input-sm",
          typ         := "search",
          placeholder := I18n.t(UiKeys.groupsFilterNamePlaceholder),
          controlled(value <-- searchInputVar.signal, onInput.mapToValue --> searchInputVar.writer),
          onInput.mapToValue --> searchTypedBus.writer,
        ),
      ),
      label(
        cls := "form-control",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.groupsFilterTagLabel)),
        input(
          cls         := "input input-sm",
          typ         := "search",
          placeholder := I18n.t(UiKeys.groupsFilterTagPlaceholder),
          controlled(value <-- tagInputVar.signal, onInput.mapToValue --> tagInputVar.writer),
          onInput.mapToValue --> tagTypedBus.writer,
        ),
      ),
    )
  }

  /** "No study groups yet" when there is truly nothing to show, "No matching study groups" once a filter is what
    * narrowed it to zero — the same split `AdminUsersPage`'s summary draws between `adminUsersEmpty` and a filtered
    * search finding nothing.
    */
  private def emptyMessage(query: GroupQuery): String = {
    if (query.search.nonEmpty || query.tag.nonEmpty)
      I18n.t(UiKeys.groupsNoMatches)
    else
      I18n.t(UiKeys.groupsEmpty)
  }

  private def renderList(): HtmlElement = {
    val onSort = Observer[SortHeader.Sort](sort => change(_.reset(_.copy(sort = sort))))

    div(
      child.maybe <--
        groupsSignal.combineWith(querySignal).map { case (list, query) =>
          Option.when(list.isEmpty)(p(cls := "text-sm opacity-70", emptyMessage(query)))
        },
      div(
        cls := "overflow-x-auto",
        table(
          cls := "table",
          thead(
            tr(
              SortHeader.render(I18n.t(UiKeys.groupsColName), GroupSort.name, sortSignal, onSort),
              th(I18n.t(UiKeys.groupsColMembers)),
              th(I18n.t(UiKeys.groupsColTags)),
              th(),
            )
          ),
          tbody(children <-- groupsSignal.map(_.map(renderRow))),
        ),
      ),
    )
  }

  private def renderRow(group: Group): HtmlElement = {
    tr(
      td(
        a(
          cls := "link link-hover",
          AppRouter.router.navigateTo(Page.GroupDetail(group.id)),
          group.name,
        )
      ),
      td(I18n.plural(UiKeys.groupsMemberCount, group.memberCount)),
      td(I18n.plural(UiKeys.groupsTagCount, group.tagCount)),
      td(
        group.viewerRole.map {
          case GroupRole.Admin  => span(cls := "badge badge-primary", I18n.t(UiKeys.groupsRoleAdmin))
          case GroupRole.Member => span(cls := "badge", I18n.t(UiKeys.groupsRoleMember))
        }
      ),
    )
  }
}
