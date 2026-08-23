package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiError, GameApiClient}
import gathedge.frontend.components.{Alert, AppShell, Formats, GameHeader, Labels, Pagination, SortHeader}
import gathedge.frontend.listing.GamePlayQuery
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.AnswerOutcome
import gathedge.shared.dto.{GameAnswerResult, GameDetail, GamePlayDetail, GamePlayPage, GamePlaySort, GamePlaySummary}
import gathedge.shared.i18n.UiKeys

/** A tracked game's owner-facing plays listing: who played `slug` and how they scored, paged/sorted/filtered the same
  * way `AdminUsersPage` is, plus a per-play detail modal.
  *
  * Ownership is enforced server-side, not here — `GameInstancePage` only links here when `GameOwnership.isOwned(slug)`
  * and `GameDetail.trackResults` both hold, but the 403/409 this page's own requests can still answer (a stale local
  * hint, a different account, or a game that never turned tracking on) is what actually enforces it.
  */
object GameResultsPage {

  def render(slug: String, query: Signal[GamePlayQuery], onQuery: Observer[GamePlayQuery]): HtmlElement = {
    AppShell.render(Page.GameResults(slug), new GameResultsPage(slug, query, onQuery).render())
  }
}

private class GameResultsPage(slug: String, pageQuery: Signal[GamePlayQuery], onQuery: Observer[GamePlayQuery]) {

  private val querySignal = pageQuery.distinct

  /** The base game's own name and stored language pair, for [[GameHeader]] — fetched once on mount alongside the plays
    * listing, independent of paging/sorting/search. `None` while loading; a load failure surfaces through [[errorVar]]
    * same as the listing's own.
    */
  private val gameVar = Var(Option.empty[GameDetail])

  private val playsVar    = Var(List.empty[GamePlaySummary])
  private val playsSignal = playsVar.signal

  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct

  private val changeBus = new EventBus[GamePlayQuery => GamePlayQuery]()

  private def change(edit: GamePlayQuery => GamePlayQuery): Unit = changeBus.emit(edit)

  // Same write-follows-the-query trick as `AdminUsersPage.searchInputVar`/`searchTypedBus` — see that page's doc
  // comment for why the box cannot be a plain two-way binding on the query itself.
  private val searchInputVar = Var("")
  private val searchTypedBus = new EventBus[String]()

  private val errorVar: Var[Option[String]] = Var(None)

  /** Set instead of [[errorVar]] when the listing answers `409` — `slug` exists and is owned, it simply never turned on
    * `trackResults`. Worded through its own key rather than the server's `err.message` so the page's copy stays
    * `ui.`-namespaced, per the project's i18n rule.
    */
  private val notTrackedVar = Var(false)

  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  private val reloadBus    = new EventBus[Unit]()
  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  private val searchDebounceMs = 300

  /** The ids on the currently loaded page, in the order shown — what the modal's prev/next arrows step through. Only
    * within this page: crossing a page boundary would need prefetching the neighbour or an extra round trip per edge
    * click, so the arrows simply disable at either end of what is already loaded rather than reaching past it.
    */
  private val currentPageIdsVar = Var(List.empty[Long])

  private val selectedPlayIdVar: Var[Option[Long]]       = Var(None)
  private val playDetailVar: Var[Option[GamePlayDetail]] = Var(None)
  private val modalOpenVar                               = Var(false)
  private val modalErrorVar: Var[Option[String]]         = Var(None)

  private val viewBus = new EventBus[Long]()
  private val stepBus = new EventBus[Int]()

  def render(): HtmlElement = {
    div(
      div(
        cls := "mb-4",
        h1(cls := "text-2xl font-bold", I18n.t(UiKeys.gameResultsTitle)),
        child.maybe <-- gameVar.signal.map(
          _.map(game => GameHeader.render(game.name, game.sourceLanguage, game.targetLanguage))
        ),
      ),
      Alert.maybeError(errorVar.signal),
      child.maybe <-- notTrackedVar.signal.map(Option.when(_)(Alert.info(I18n.t(UiKeys.gameResultsNotTracked)))),
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
      renderModal(),
      changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) } --> onQuery,
      querySignal.map(_.search).distinct --> searchInputVar.writer,
      searchTypedBus.events.debounce(searchDebounceMs).withCurrentValueOf(querySignal) -->
        Observer[(String, GamePlayQuery)] { case (typed, current) =>
          val wanted = typed.trim
          if (wanted != current.search) {
            change(_.reset(_.copy(search = wanted)))
          }
        },
      // The game's own name/language pair for `GameHeader` — independent of paging/sorting, so fetched once off
      // `reloadBus` directly rather than `listRequests` (which re-fires on every query change).
      reloadBus.events.flatMapSwitch(_ => GameApiClient.get(slug)) -->
        Observer[Either[ApiError, GameDetail]] {
          case Right(detail) =>
            gameVar.set(Some(detail))
          case Left(err)     =>
            errorVar.set(Some(err.message))
        },
      listRequests -->
        Observer[GamePlayQuery](_ => Var.set(loadingVar -> true, errorVar -> None, notTrackedVar -> false)),
      listRequests.flatMapSwitch(load) -->
        Observer[Either[ApiError, GamePlayPage]] {
          case Right(result)                  =>
            Var.set(
              playsVar          -> result.items,
              totalVar          -> result.total,
              currentPageIdsVar -> result.items.map(_.playId),
              loadingVar        -> false,
              errorVar          -> None,
            )
          case Left(err) if err.status == 409 =>
            Var.set(
              loadingVar        -> false,
              notTrackedVar     -> true,
              playsVar          -> Nil,
              totalVar          -> 0L,
              currentPageIdsVar -> Nil,
            )
          case Left(err)                      =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message))
        },
      viewBus.events -->
        Observer[Long] { id =>
          Var.set(
            selectedPlayIdVar -> Some(id),
            modalOpenVar      -> true,
            playDetailVar     -> None,
            modalErrorVar     -> None,
          )
        },
      stepBus.events --> Observer[Int](step),
      // Fires for both a fresh `viewBus` open and a `step` — either one changes the selected id.
      selectedPlayIdVar.signal.updates
        .collect { case Some(id) => id }
        .flatMapSwitch(id => GameApiClient.getPlayDetail(slug, id)) -->
        Observer[Either[ApiError, GamePlayDetail]] {
          case Right(detail) =>
            Var.set(playDetailVar -> Some(detail), modalErrorVar -> None)
          case Left(err)     =>
            Var.set(playDetailVar -> None, modalErrorVar -> Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def load(query: GamePlayQuery): EventStream[Either[ApiError, GamePlayPage]] = {
    GameApiClient.listPlays(
      slug = slug,
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      search = Option(query.search).filter(_.nonEmpty),
    )
  }

  private def step(delta: Int): Unit = {
    val ids    = currentPageIdsVar.now()
    val target = for {
      current <- selectedPlayIdVar.now()
      index    = ids.indexOf(current)
      if index >= 0
      next    <- ids.lift(index + delta)
    } yield next
    target.foreach(id => Var.set(selectedPlayIdVar -> Some(id), playDetailVar -> None, modalErrorVar -> None))
  }

  private def summaryOf(total: Long): String = {
    if (total <= 0L)
      I18n.t(UiKeys.gameResultsEmpty)
    else
      I18n.plural(UiKeys.gameResultsCount, total)
  }

  private def renderSearch(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-2 mb-4",
      label(
        cls := "form-control",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.gameResultsFilterLabel)),
        input(
          cls         := "input input-sm",
          typ         := "search",
          placeholder := I18n.t(UiKeys.gameResultsFilterPlaceholder),
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
            // No `ORDER BY` could produce this column without a join this listing deliberately avoids — see
            // `GameRepository.matchingPlays`'s doc comment. Filterable, via the search box above, but not sortable.
            th(I18n.t(UiKeys.gameResultsPlayerCol)),
            SortHeader.render(I18n.t(UiKeys.gameResultsScoreCol), GamePlaySort.score, sortSignal, onSort),
            SortHeader.render(I18n.t(UiKeys.gameResultsWordCountCol), GamePlaySort.wordCount, sortSignal, onSort),
            th(I18n.t(UiKeys.gameResultsVariantCol)),
            SortHeader.render(I18n.t(UiKeys.gameResultsStartedCol), GamePlaySort.startedAt, sortSignal, onSort),
            th(),
          )
        ),
        tbody(
          children <--
            playsSignal.map(_.map(renderRow))
        ),
      ),
    )
  }

  private def renderRow(play: GamePlaySummary): HtmlElement = {
    tr(
      cls := "hover",
      td(play.playerEmail.getOrElse(I18n.t(UiKeys.gameResultsGuestBadge))),
      td(s"${play.score} / ${play.maxScore}"),
      td(play.wordCount.toString),
      td(Labels.variant(play.variant)),
      td(Formats.dateTime(play.startedAt)),
      td(
        button(
          cls := "btn btn-ghost btn-xs",
          typ := "button",
          I18n.t(UiKeys.gameResultsViewButton),
          onClick.mapTo(play.playId) --> viewBus.writer,
        )
      ),
    )
  }

  /** Copied from `GameInstancePage.renderQrModal`'s pattern exactly: a `div.modal` with `modal-open` toggled off a
    * `Var[Boolean]`, not `HTMLDialogElement.showModal` — unimplemented in jsdom, which the frontend specs run under.
    */
  private def renderModal(): HtmlElement = {
    val indexSignal  = currentPageIdsVar.signal.combineWith(selectedPlayIdVar.signal).map { case (ids, selected) =>
      selected.map(ids.indexOf)
    }
    val atFirst      = indexSignal.map(index => index.forall(_ <= 0)).distinct
    val atLastSignal = {
      currentPageIdsVar.signal
        .combineWith(indexSignal)
        .map { case (ids, index) => index.forall(i => i < 0 || i >= ids.size - 1) }
        .distinct
    }

    div(
      cls := "modal",
      cls("modal-open") <-- modalOpenVar.signal,
      div(
        cls   := "modal-box max-w-2xl",
        div(
          cls := "flex items-center justify-between mb-2",
          h3(cls := "font-semibold text-lg", I18n.t(UiKeys.gameResultsModalTitle)),
          div(
            cls  := "flex items-center gap-1",
            button(
              cls        := "btn btn-ghost btn-sm",
              typ        := "button",
              aria.label := I18n.t(UiKeys.gameResultsModalPrev),
              disabled <-- atFirst,
              "‹",
              onClick.mapTo(-1) --> stepBus.writer,
            ),
            button(
              cls        := "btn btn-ghost btn-sm",
              typ        := "button",
              aria.label := I18n.t(UiKeys.gameResultsModalNext),
              disabled <-- atLastSignal,
              "›",
              onClick.mapTo(1) --> stepBus.writer,
            ),
          ),
        ),
        child <--
          playDetailVar.signal.combineWith(modalErrorVar.signal).map {
            case (_, Some(err))    =>
              p(cls := "text-error text-sm", err)
            case (None, None)      =>
              span(cls := "loading loading-spinner")
            case (Some(detail), _) =>
              renderModalBody(detail)
          },
        div(
          cls := "modal-action",
          button(
            cls := "btn",
            typ := "button",
            I18n.t(UiKeys.gameResultsModalClose),
            onClick.mapToUnit --> Observer[Unit](_ => modalOpenVar.set(false)),
          ),
        ),
      ),
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => modalOpenVar.set(false))),
    )
  }

  private def renderModalBody(detail: GamePlayDetail): HtmlElement = {
    div(
      p(cls := "text-sm opacity-70", detail.playerEmail.getOrElse(I18n.t(UiKeys.gameResultsGuestBadge))),
      p(cls := "font-bold mb-2", s"${detail.score} / ${detail.maxScore}"),
      p(cls := "text-sm opacity-70 mb-2", Labels.variant(detail.variant)),
      renderAnswersTable(detail.answers),
    )
  }

  private def renderAnswersTable(answers: List[GameAnswerResult]): HtmlElement = {
    div(
      cls := "overflow-x-auto",
      table(
        cls := "table table-sm",
        thead(
          tr(
            th(I18n.t(UiKeys.gameInstanceResultsWordCol)),
            th(I18n.t(UiKeys.gameInstanceResultsExpectedCol)),
            th(I18n.t(UiKeys.gameInstanceResultsAnswerCol)),
            th(I18n.t(UiKeys.gameInstanceResultsOutcomeCol)),
          )
        ),
        tbody(answers.map(renderAnswerRow)),
      ),
    )
  }

  private def renderAnswerRow(answer: GameAnswerResult): HtmlElement = {
    tr(
      cls := "hover",
      td(answer.wordText),
      td(answer.expectedText),
      td(answer.givenText),
      td(renderOutcomeBadge(answer.outcome)),
    )
  }

  /** Same styling `GameInstancePage.renderOutcomeBadge` uses — kept as its own copy rather than shared, since neither
    * page exposes the other's private helpers.
    */
  private def renderOutcomeBadge(outcome: AnswerOutcome): HtmlElement = {
    val style = outcome match {
      case AnswerOutcome.Correct =>
        "badge-success badge-soft"
      case AnswerOutcome.Typo    =>
        "badge-warning"
      case AnswerOutcome.Wrong   =>
        "badge-error"
    }
    span(cls := s"badge $style", Labels.gameOutcome(outcome))
  }
}
