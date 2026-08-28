package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient}
import gathedge.frontend.components.{Alert, AppShell, Formats, Labels, Pagination, SortHeader}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.AllGameQuery
import gathedge.shared.dto.{AllGamePage, AllGameSort, AllGameSummary}
import gathedge.shared.i18n.UiKeys

/** Every account's games: name, tags, language pair, how many times each was played, and when it was created — see
  * `GameService.allGames`.
  *
  * Built to the same shape as `GameResultsPage`/`MyPlayHistoryPage`: a card table with sortable headings, a filter box
  * (here a substring of the game's name), and server-side paging. It carries its whole listing state in the URL, so it
  * takes a `Signal[AllGameQuery]` and an `Observer[AllGameQuery]` the same way those pages do; `App` supplies both.
  * There is no per-row detail modal — a game's own page is one click away on its name.
  */
object AllGamesPage {

  def render(query: Signal[AllGameQuery], onQuery: Observer[AllGameQuery]): HtmlElement = {
    AppShell.render(Page.AllGames(), new AllGamesPage(query, onQuery).render())
  }
}

private class AllGamesPage(pageQuery: Signal[AllGameQuery], onQuery: Observer[AllGameQuery]) {

  private val querySignal = pageQuery.distinct

  private val gamesVar    = Var(List.empty[AllGameSummary])
  private val gamesSignal = gamesVar.signal

  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct

  private val changeBus = new EventBus[AllGameQuery => AllGameQuery]()

  private def change(edit: AllGameQuery => AllGameQuery): Unit = changeBus.emit(edit)

  // Same write-follows-the-query trick as `GameResultsPage`/`MyPlayHistoryPage` — the box cannot be a plain two-way
  // binding on the query itself.
  private val searchInputVar   = Var("")
  private val searchTypedBus   = new EventBus[String]()
  private val searchDebounceMs = 300

  private val errorVar: Var[Option[String]] = Var(None)

  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  private val reloadBus    = new EventBus[Unit]()
  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  def render(): HtmlElement = {
    div(
      div(
        cls := "mb-4",
        h1(cls := "text-2xl font-bold", I18n.t(UiKeys.allGamesTitle)),
      ),
      Alert.maybeError(errorVar.signal),
      renderSearch(),
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
      changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) } --> onQuery,
      querySignal.map(_.search).distinct --> searchInputVar.writer,
      searchTypedBus.events.debounce(searchDebounceMs).withCurrentValueOf(querySignal) -->
        Observer[(String, AllGameQuery)] { case (typed, current) =>
          val wanted = typed.trim
          if (wanted != current.search) {
            change(_.reset(_.copy(search = wanted)))
          }
        },
      listRequests -->
        Observer[AllGameQuery](_ => Var.set(loadingVar -> true, errorVar -> None)),
      listRequests.flatMapSwitch(load) -->
        Observer[Either[ApiError, AllGamePage]] {
          case Right(result) =>
            Var.set(
              gamesVar   -> result.items,
              totalVar   -> result.total,
              loadingVar -> false,
              errorVar   -> None,
            )
          case Left(err)     =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def load(query: AllGameQuery): EventStream[Either[ApiError, AllGamePage]] = {
    GameApiClient.allGames(
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      search = Option(query.search).filter(_.nonEmpty),
    )
  }

  private def summaryOf(total: Long): String = {
    if (total <= 0L)
      I18n.t(UiKeys.allGamesEmpty)
    else
      I18n.plural(UiKeys.allGamesCount, total)
  }

  private def renderSearch(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-2 mb-4",
      label(
        cls := "form-control",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.allGamesFilterLabel)),
        input(
          cls         := "input input-sm",
          typ         := "search",
          placeholder := I18n.t(UiKeys.allGamesFilterPlaceholder),
          controlled(value <-- searchInputVar.signal, onInput.mapToValue --> searchInputVar.writer),
          onInput.mapToValue --> searchTypedBus.writer,
        ),
      ),
    )
  }

  private def renderTable(): HtmlElement = {
    val onSort = Observer[SortHeader.Sort](sort => change(_.reset(_.copy(sort = sort))))

    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table",
        thead(
          tr(
            SortHeader.render(I18n.t(UiKeys.allGamesNameCol), AllGameSort.name, sortSignal, onSort),
            // Tags, the language pair and the play count are filterable/readable but not sortable — see `AllGameSort`.
            th(I18n.t(UiKeys.allGamesTagsCol)),
            th(I18n.t(UiKeys.allGamesSourceCol)),
            th(I18n.t(UiKeys.allGamesTargetCol)),
            th(I18n.t(UiKeys.allGamesPlaysCol)),
            SortHeader.render(I18n.t(UiKeys.allGamesCreatedCol), AllGameSort.createdAt, sortSignal, onSort),
          )
        ),
        tbody(
          children <--
            gamesSignal.map(_.map(renderRow))
        ),
      ),
    )
  }

  private def renderRow(game: AllGameSummary): HtmlElement = {
    tr(
      cls := "hover",
      td(a(cls := "link link-hover", AppRouter.router.navigateTo(Page.GameInstance(game.slug)), game.name)),
      td(renderTags(game.tagNames)),
      td(Labels.language(game.sourceLanguage)),
      td(Labels.language(game.targetLanguage)),
      td(game.playCount.toString),
      td(Formats.dateTime(game.createdAt)),
    )
  }

  private def renderTags(tagNames: List[String]): HtmlElement = {
    div(
      cls := "flex flex-wrap gap-1",
      tagNames.map(name => span(cls := "badge badge-primary badge-soft badge-sm", name)),
    )
  }
}
