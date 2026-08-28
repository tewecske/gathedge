package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient, GameReplay}
import gathedge.frontend.components.{
  Alert,
  AppShell,
  Formats,
  GameAnswersTable,
  GameHeader,
  Labels,
  Pagination,
  SortHeader,
}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.MyPlayQuery
import gathedge.frontend.state.{PendingPlay, PlayHandoff}
import gathedge.shared.dto.{GamePlaySort, GameResults, MyPlayPage, MyPlaySummary}
import gathedge.shared.i18n.UiKeys

/** The signed-in caller's own play history across every game — see `GameService.myPlays`. Always the caller's own data,
  * unlike the owner-facing `GameResultsPage`.
  *
  * Built to the same shape as `GameResultsPage`: a card table with sortable headings, a filter box (here a substring of
  * the game's name, not the player's address), server-side paging, and a per-row detail modal. It carries its whole
  * listing state in the URL, so it takes a `Signal[MyPlayQuery]` and an `Observer[MyPlayQuery]` the same way the admin
  * listings and `GameResultsPage` do; `App` supplies both.
  *
  * The modal fetches through `GameApiClient.getResults` — the player-facing equivalent of `GameResultsPage`'s
  * owner-facing `getPlayDetail` modal, minus a player identity line since every row here is always "me".
  */
object MyPlayHistoryPage {

  def render(query: Signal[MyPlayQuery], onQuery: Observer[MyPlayQuery]): HtmlElement = {
    AppShell.render(Page.MyPlays(), new MyPlayHistoryPage(query, onQuery).render())
  }
}

private class MyPlayHistoryPage(pageQuery: Signal[MyPlayQuery], onQuery: Observer[MyPlayQuery]) {

  private val querySignal = pageQuery.distinct

  private val playsVar    = Var(List.empty[MyPlaySummary])
  private val playsSignal = playsVar.signal

  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct

  private val changeBus = new EventBus[MyPlayQuery => MyPlayQuery]()

  private def change(edit: MyPlayQuery => MyPlayQuery): Unit = changeBus.emit(edit)

  // Same write-follows-the-query trick as `GameResultsPage`/`AdminUsersPage` — the box cannot be a plain two-way
  // binding on the query itself.
  private val searchInputVar   = Var("")
  private val searchTypedBus   = new EventBus[String]()
  private val searchDebounceMs = 300

  private val errorVar: Var[Option[String]] = Var(None)

  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  private val reloadBus    = new EventBus[Unit]()
  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  /** The ids on the currently loaded page, in the order shown — what the modal's prev/next arrows step through, the
    * same "only within this page" rule `GameResultsPage.currentPageIdsVar` follows.
    */
  private val currentPageIdsVar = Var(List.empty[Long])

  private val selectedPlayIdVar: Var[Option[Long]] = Var(None)
  private val resultsVar: Var[Option[GameResults]] = Var(None)
  private val modalOpenVar                         = Var(false)
  private val modalErrorVar: Var[Option[String]]   = Var(None)

  private val viewBus      = new EventBus[Long]()
  private val stepBus      = new EventBus[Int]()
  private val playAgainBus = new EventBus[MyPlaySummary]()
  private val continueBus  = new EventBus[MyPlaySummary]()

  def render(): HtmlElement = {
    div(
      div(
        cls := "mb-4",
        h1(cls := "text-2xl font-bold", I18n.t(UiKeys.myPlaysTitle)),
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
      renderModal(),
      changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) } --> onQuery,
      querySignal.map(_.search).distinct --> searchInputVar.writer,
      searchTypedBus.events.debounce(searchDebounceMs).withCurrentValueOf(querySignal) -->
        Observer[(String, MyPlayQuery)] { case (typed, current) =>
          val wanted = typed.trim
          if (wanted != current.search) {
            change(_.reset(_.copy(search = wanted)))
          }
        },
      listRequests -->
        Observer[MyPlayQuery](_ => Var.set(loadingVar -> true, errorVar -> None)),
      listRequests.flatMapSwitch(load) -->
        Observer[Either[ApiError, MyPlayPage]] {
          case Right(result) =>
            Var.set(
              playsVar          -> result.items,
              totalVar          -> result.total,
              currentPageIdsVar -> result.items.map(_.playId),
              loadingVar        -> false,
              errorVar          -> None,
            )
          case Left(err)     =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message))
        },
      viewBus.events -->
        Observer[Long] { id =>
          Var.set(
            selectedPlayIdVar -> Some(id),
            modalOpenVar      -> true,
            resultsVar        -> None,
            modalErrorVar     -> None,
          )
        },
      stepBus.events --> Observer[Int](step),
      // Fires for both a fresh `viewBus` open and a `step` — either one changes the selected id.
      selectedPlayIdVar.signal.updates
        .collect { case Some(id) => id }
        .flatMapSwitch(GameApiClient.getResults) -->
        Observer[Either[ApiError, GameResults]] {
          case Right(results) =>
            Var.set(resultsVar -> Some(results), modalErrorVar -> None)
          case Left(err)      =>
            Var.set(resultsVar -> None, modalErrorVar -> Some(err.message))
        },
      playAgainBus.events.flatMapSwitch(play => GameReplay.start(play.gameSlug, play.variant)) -->
        Observer[Either[ApiError, GameReplay.Started]] {
          case Right(result) =>
            PendingPlay.set(result.playId, PlayHandoff(result.gameName, result.wordCount, result.variant))
            AppRouter.router.pushState(Page.GamePlay(result.slug, result.playId))
          case Left(err)     =>
            errorVar.set(Some(err.message))
        },
      // No `GameReplay.start` here: the play already exists on the server, so this hands off its own
      // name/wordCount/variant (all already on the row) and re-enters the loop, which resumes from the next
      // unanswered word.
      continueBus.events --> Observer[MyPlaySummary] { play =>
        PendingPlay.set(play.playId, PlayHandoff(play.gameName, play.wordCount, play.variant))
        AppRouter.router.pushState(Page.GamePlay(play.gameSlug, play.playId))
      },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def load(query: MyPlayQuery): EventStream[Either[ApiError, MyPlayPage]] = {
    GameApiClient.myPlays(
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
    target.foreach(id => Var.set(selectedPlayIdVar -> Some(id), resultsVar -> None, modalErrorVar -> None))
  }

  private def summaryOf(total: Long): String = {
    if (total <= 0L)
      I18n.t(UiKeys.myPlaysEmpty)
    else
      I18n.plural(UiKeys.myPlaysCount, total)
  }

  private def renderSearch(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-2 mb-4",
      label(
        cls := "form-control",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.myPlaysFilterLabel)),
        input(
          cls         := "input input-sm",
          typ         := "search",
          placeholder := I18n.t(UiKeys.myPlaysFilterPlaceholder),
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
            // The game is filterable, via the search box above, but not sortable — ordering by it would need a join
            // the listing avoids, the same split `GameResultsPage`'s player column draws.
            th(I18n.t(UiKeys.myPlaysGameCol)),
            SortHeader.render(I18n.t(UiKeys.myPlaysScoreCol), GamePlaySort.score, sortSignal, onSort),
            SortHeader.render(I18n.t(UiKeys.myPlaysWordsCol), GamePlaySort.wordCount, sortSignal, onSort),
            th(I18n.t(UiKeys.gameResultsVariantCol)),
            SortHeader.render(I18n.t(UiKeys.myPlaysStartedCol), GamePlaySort.startedAt, sortSignal, onSort),
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

  private def renderRow(play: MyPlaySummary): HtmlElement = {
    tr(
      cls := "hover",
      td(
        a(
          cls := "link link-hover",
          AppRouter.router.navigateTo(Page.GameInstance(play.gameSlug)),
          play.gameName,
        )
      ),
      td(
        if (play.finishedAt.isEmpty)
          span(cls := "badge badge-ghost badge-sm", I18n.t(UiKeys.myPlaysInProgress))
        else
          span(s"${play.score} / ${play.maxScore}")
      ),
      td(play.wordCount.toString),
      td(Labels.variant(play.variant)),
      td(Formats.dateTime(play.startedAt)),
      td(
        div(
          cls := "flex gap-1",
          button(
            cls := "btn btn-ghost btn-xs",
            typ := "button",
            I18n.t(UiKeys.gameResultsViewButton),
            onClick.mapTo(play.playId) --> viewBus.writer,
          ),
          // An unfinished play resumes where it left off; a finished one can only be replayed fresh.
          if (play.finishedAt.isEmpty) {
            button(
              cls := "btn btn-ghost btn-xs",
              typ := "button",
              I18n.t(UiKeys.myPlaysContinueButton),
              onClick.mapTo(play) --> continueBus.writer,
            )
          } else {
            button(
              cls := "btn btn-ghost btn-xs",
              typ := "button",
              I18n.t(UiKeys.gameInstancePlayAgain),
              onClick.mapTo(play) --> playAgainBus.writer,
            )
          },
        )
      ),
    )
  }

  /** The selected row's `gameName`, looked up from the already-loaded [[playsVar]] — a history row spans every game, so
    * unlike `GameResultsPage`'s single-game modal, [[GameHeader]] needs a name resolved per selected play, and
    * `GameResults` itself (the modal's own fetch) never carries one.
    */
  private val selectedGameNameSignal: Signal[Option[String]] = {
    playsVar.signal.combineWith(selectedPlayIdVar.signal).map { case (plays, selected) =>
      for {
        id  <- selected
        row <- plays.find(_.playId == id)
      } yield row.gameName
    }
  }

  /** Same `div.modal` + `Var[Boolean]` pattern `GameResultsPage.renderModal` uses, for the same jsdom reason. */
  private def renderModal(): HtmlElement = {
    val indexSignal = currentPageIdsVar.signal.combineWith(selectedPlayIdVar.signal).map { case (ids, selected) =>
      selected.map(ids.indexOf)
    }
    val atFirst     = indexSignal.map(index => index.forall(_ <= 0)).distinct
    val atLast      = {
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
              disabled <-- atLast,
              "›",
              onClick.mapTo(1) --> stepBus.writer,
            ),
          ),
        ),
        child <--
          resultsVar.signal.combineWith(modalErrorVar.signal, selectedGameNameSignal).map {
            case (_, Some(err), _)           =>
              p(cls := "text-error text-sm", err)
            case (None, None, _)             =>
              span(cls := "loading loading-spinner")
            case (Some(results), _, nameOpt) =>
              renderModalBody(nameOpt.getOrElse(""), results)
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

  private def renderModalBody(gameName: String, results: GameResults): HtmlElement = {
    div(
      GameHeader.render(gameName, results.variant),
      p(cls := "font-bold mb-2 mt-2", s"${results.score} / ${results.maxScore}"),
      GameAnswersTable.render(results.answers),
    )
  }
}
