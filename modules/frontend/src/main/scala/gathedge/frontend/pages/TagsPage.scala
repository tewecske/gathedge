package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, HelpIcon, Labels, Pagination, SortHeader, TagImportDialog}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.TagQuery
import gathedge.frontend.state.AppState
import gathedge.frontend.util.Download
import gathedge.shared.domain.{Tag, TagScope}
import gathedge.shared.dto.{TagExportFile, TagPage, TagSort}
import gathedge.shared.i18n.UiKeys
import zio.json._

/** The whole wordlist catalog — every wordlist there is — laid out as a paged table, the sibling of `WordsPage` for the
  * vocabulary. Reached from the navigation bar and from the collection bar's "All tags" button.
  *
  * Everyone sees every wordlist, database-paged and sorted the same way the two admin listings are. Unsorted, the order
  * is the one the table's old section headings used to carry — a signed-in reader's own wordlists, then any a group
  * they belong to has opened to them, then everyone else's — see `TagQuery`'s and `WordService.listTagsPaged`'s doc
  * comments; `scope` is the filter that replaces picking a section by eye. A signed-out visitor sees the same table
  * with the scope filter hidden, since none of it is theirs to narrow by. The "New wordlist", "Export all" and "Import"
  * controls are shown only when signed in ("New wordlist" always, since it mints a guest).
  */
object TagsPage {

  def render(
    query: Signal[TagQuery],
    onQuery: Observer[TagQuery],
  ): HtmlElement = {
    AppShell.render(Page.Tags(), new TagsPage(query, onQuery).render())
  }
}

private class TagsPage(
  pageQuery: Signal[TagQuery],
  onQuery: Observer[TagQuery],
) {

  /** `.distinct` because every reader here treats an emission as "ask the server again". */
  private val querySignal = pageQuery.distinct

  private val tagsVar    = Var(List.empty[Tag])
  private val tagsSignal = tagsVar.signal

  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct

  private val signedInSignal = AppState.isSignedInSignal

  /** Every way this page asks for a different listing, as edits applied to whatever the address bar says — the
    * arrangement `WordsPage`/`AdminUsersPage` use, and for the same reason: the state is in the URL, not in a local
    * `Var`.
    */
  private val changeBus = new EventBus[TagQuery => TagQuery]()

  private def change(edit: TagQuery => TagQuery): Unit = changeBus.emit(edit)

  /** What the search box shows: seeded from the query, never read back. The reader's keystrokes travel on
    * [[searchTypedBus]] instead — see the long note in `AdminUsersPage`, which this follows exactly.
    */
  private val searchInputVar = Var("")
  private val searchTypedBus = new EventBus[String]()

  private val reloadBus    = new EventBus[Unit]()
  private val exportAllBus = new EventBus[Unit]()

  private val errorVar: Var[Option[String]] = Var(None)

  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  private val importDialog = new TagImportDialog(onImported = Observer[Unit](_ => reloadBus.emit(())))

  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  private val searchDebounceMs = 300

  def render(): HtmlElement = {
    div(
      cls := "max-w-3xl mx-auto",
      Alert.maybeError(errorVar.signal),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          div(
            cls := "flex flex-wrap items-center justify-between gap-2",
            h1(
              cls := "card-title text-2xl flex items-center gap-1",
              span(I18n.t(UiKeys.tagsListTitle)),
              HelpIcon.render(I18n.t(UiKeys.helpTags)),
            ),
            div(
              cls := "flex gap-2",
              a(
                cls := "btn btn-sm btn-primary",
                AppRouter.router.navigateTo(Page.TagCreate),
                I18n.t(UiKeys.tagsCreate),
              ),
              // Both act on the caller's own wordlists, so a signed-out visitor — who owns none — is not shown them.
              child.maybe <-- signedInSignal.map(
                Option.when(_)(
                  button(
                    cls := "btn btn-sm",
                    typ := "button",
                    I18n.t(UiKeys.tagsExportAllButton),
                    onClick.mapToUnit --> exportAllBus.writer,
                  )
                )
              ),
              child.maybe <-- signedInSignal.map(Option.when(_)(importDialog.renderButton())),
            ),
          ),
          renderFilters(),
          renderTable(),
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
      importDialog.renderModal(),
      queryChanges --> onQuery,
      querySignal.map(_.search).distinct --> searchInputVar.writer,
      searchTypedBus.events.debounce(searchDebounceMs).withCurrentValueOf(querySignal) -->
        Observer[(String, TagQuery)] { case (typed, current) =>
          val wanted = typed.trim
          if (wanted != current.search) {
            change(_.reset(_.copy(search = wanted)))
          }
        },
      listRequests --> Observer[TagQuery](_ => Var.set(loadingVar -> true, errorVar -> None)),
      listRequests.flatMapSwitch(load) -->
        Observer[Either[ApiError, TagPage]] {
          case Right(result) =>
            Var.set(tagsVar -> result.items, totalVar -> result.total, loadingVar -> false, errorVar -> None)
          case Left(err)     =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message))
        },
      exportAllBus.events.flatMapSwitch(_ => WordApiClient.exportOwnedTags) -->
        Observer[Either[ApiError, TagExportFile]] {
          case Right(file) =>
            Download.text("my-tags.json", file.toJson)
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  /** Every way this page's own query can change, resolved against whatever the address bar currently says — the same
    * value both [[render]]'s `onQuery` wiring and the request stream below need.
    */
  private val queryChanges: EventStream[TagQuery] =
    changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) }

  private def load(query: TagQuery): EventStream[Either[ApiError, TagPage]] = {
    WordApiClient.listTagsPage(
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      search = Option(query.search).filter(_.nonEmpty),
      scope = Some(query.scope),
    )
  }

  private def summaryOf(total: Long): String = {
    if (total <= 0L)
      I18n.t(UiKeys.tagsListEmpty)
    else
      I18n.plural(UiKeys.tagsListCount, total)
  }

  private def renderFilters(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-2 mb-4",
      label(
        cls := "flex flex-col gap-1 grow",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.tagsListSearchLabel)),
        input(
          cls         := "input w-full",
          typ         := "search",
          placeholder := I18n.t(UiKeys.tagsListSearchPlaceholder),
          controlled(value <-- searchInputVar.signal, onInput.mapToValue --> searchInputVar.writer),
          onInput.mapToValue --> searchTypedBus.writer,
        ),
      ),
      child.maybe <-- signedInSignal.map(Option.when(_)(renderScopeFilter())),
      child.maybe <-- querySignal
        .map(_.scope != TagScope.All)
        .distinct
        .map(Option.when(_)(renderResetFilters())),
    )
  }

  /** Narrows to one of the three groups the table's section headings used to be — see `TagQuery`'s doc comment. Shown
    * only when signed in: a visitor owns nothing and belongs to no group, so `Mine`/`Group` would only ever answer
    * empty for them.
    */
  private def renderScopeFilter(): HtmlElement = {
    label(
      cls := "flex flex-col gap-1",
      span(cls := "label-text text-xs", I18n.t(UiKeys.tagsListScopeLabel)),
      select(
        cls    := "select select-sm w-48",
        TagScope.all.map(scope => option(value := TagScope.code(scope), scopeLabel(scope))),
        controlled(
          value <-- querySignal.map(query => TagScope.code(query.scope)),
          onChange.mapToValue --> Observer[String] { code =>
            change(_.reset(_.copy(scope = TagScope.fromString(code))))
          },
        ),
      ),
    )
  }

  private def scopeLabel(scope: TagScope): String = {
    scope match {
      case TagScope.All   =>
        I18n.t(UiKeys.tagsListTitle)
      case TagScope.Mine  =>
        I18n.t(UiKeys.tagsListYours)
      case TagScope.Group =>
        I18n.t(UiKeys.tagsListScopeGroup)
      case TagScope.Other =>
        I18n.t(UiKeys.tagsListOthers)
    }
  }

  /** Shown only once the scope narrows away from [[TagScope.All]] — a listing already unfiltered has nothing to reset.
    * Keeps the search term, like `WordsPage.renderResetFilters` does.
    */
  private def renderResetFilters(): HtmlElement = {
    button(
      typ := "button",
      cls := "btn btn-soft btn-sm",
      I18n.t(UiKeys.tagsListResetFilters),
      onClick.mapToUnit --> Observer[Unit] { _ =>
        change(_.reset(current => TagQuery.default.copy(search = current.search)))
      },
    )
  }

  private def renderTable(): HtmlElement = {
    val onSort = Observer[SortHeader.Sort](sort => change(_.reset(_.copy(sort = sort))))

    div(
      cls := "overflow-x-auto",
      table(
        cls := "table",
        thead(
          tr(
            SortHeader.render(I18n.t(UiKeys.tagsListColName), TagSort.name, sortSignal, onSort),
            th(I18n.t(UiKeys.tagsListColOwner)),
            SortHeader.render(I18n.t(UiKeys.tagsListColWords), TagSort.words, sortSignal, onSort),
          )
        ),
        tbody(children <-- tagsSignal.splitSeq(_.id)(row => renderRow(row.key, row))),
      ),
    )
  }

  private def renderRow(id: Long, row: Signal[Tag]): HtmlElement = {
    tr(
      td(
        span(cls := "font-mono text-xs opacity-70 mr-2", child.text <-- row.map(Labels.tagCodes)),
        a(
          cls    := "link link-hover",
          AppRouter.router.navigateTo(Page.TagDetail(id)),
          child.text <-- row.map(_.name),
        ),
      ),
      renderOwnerCell(row),
      td(child.text <-- row.map(_.wordCount.toString)),
    )
  }

  /** "You" for the caller's own row, the group's name (linked) for one shared through a study group, blank for
    * everybody else's — the same three-way split [[scopeLabel]] narrows by, read straight off the row instead of the
    * query. A tag's `group` is shown whenever it carries one, even outside `TagScope.Group`'s own narrower rule (which
    * also requires `editableByMe`): the badge is *information about the row*, not a restatement of the filter.
    */
  private def renderOwnerCell(row: Signal[Tag]): HtmlElement = {
    td(
      child.maybe <-- row.map { tag =>
        if (tag.ownedByMe) {
          Some(span(cls := "badge badge-sm", I18n.t(UiKeys.tagsListOwnerYou)))
        } else {
          tag.group.map(group =>
            a(cls := "link link-hover text-sm", AppRouter.router.navigateTo(Page.GroupDetail(group.id)), group.name)
          )
        }
      }
    )
  }
}
