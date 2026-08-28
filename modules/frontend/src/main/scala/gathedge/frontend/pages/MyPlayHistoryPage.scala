package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient, GameReplay}
import gathedge.frontend.components.{Alert, AppShell, GameHeader, Labels, PlayHistoryTable}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.{PendingPlay, PlayHandoff}
import gathedge.shared.domain.AnswerOutcome
import gathedge.shared.dto.{GameAnswerResult, GameResults, MyPlayPage, MyPlaySummary}
import gathedge.shared.i18n.UiKeys

/** The signed-in caller's own play history across every game — see `GameService.myPlays`. Always the caller's own data,
  * unlike the owner-facing `GameResultsPage`.
  *
  * A personal list rendered straight through with no URL-carried query state, the same shape `MyGamesPage` follows.
  * Each row opens a detail modal via `GameApiClient.getResults` — the player-facing equivalent of `GameResultsPage`'s
  * owner-facing `getPlayDetail` modal, minus a player identity line since every row here is always "me".
  */
object MyPlayHistoryPage {

  def render(): HtmlElement = {
    AppShell.render(Page.MyPlays, new MyPlayHistoryPage().render())
  }
}

private class MyPlayHistoryPage {

  private val playsVar: Var[Option[List[MyPlaySummary]]] = Var(None)
  private val errorVar: Var[Option[String]]              = Var(None)

  private val reloadBus = new EventBus[Unit]()

  /** The ids of the currently loaded plays, in the order shown — what the modal's prev/next arrows step through, same
    * as `GameResultsPage.currentPageIdsVar`.
    */
  private val currentIdsVar = Var(List.empty[Long])

  private val selectedPlayIdVar: Var[Option[Long]] = Var(None)
  private val resultsVar: Var[Option[GameResults]] = Var(None)
  private val modalOpenVar                         = Var(false)
  private val modalErrorVar: Var[Option[String]]   = Var(None)

  private val viewBus      = new EventBus[Long]()
  private val stepBus      = new EventBus[Int]()
  private val playAgainBus = new EventBus[MyPlaySummary]()

  def render(): HtmlElement = {
    div(
      cls := "p-4",
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.myPlaysTitle)),
      Alert.maybeError(errorVar.signal),
      child <-- playsVar.signal.map(renderBody),
      renderModal(),
      reloadBus.events.flatMapSwitch(_ => GameApiClient.myPlays(pageSize = Some(100))) -->
        Observer[Either[ApiError, MyPlayPage]] {
          case Right(page) =>
            Var.set(
              playsVar      -> Some(page.items),
              errorVar      -> None,
              currentIdsVar -> page.items.map(_.playId),
            )
          case Left(err)   =>
            errorVar.set(Some(err.message))
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
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def renderBody(plays: Option[List[MyPlaySummary]]): HtmlElement = {
    plays match {
      case None                       =>
        div(cls := "flex justify-center p-8", span(cls := "loading loading-spinner"))
      case Some(rows) if rows.isEmpty =>
        div(cls := "text-base-content/70", I18n.t(UiKeys.myPlaysEmpty))
      case Some(rows)                 =>
        PlayHistoryTable.render(Val(rows), onView = Some(viewBus.writer), onPlayAgain = Some(playAgainBus.writer))
    }
  }

  private def step(delta: Int): Unit = {
    val ids    = currentIdsVar.now()
    val target = for {
      current <- selectedPlayIdVar.now()
      index    = ids.indexOf(current)
      if index >= 0
      next    <- ids.lift(index + delta)
    } yield next
    target.foreach(id => Var.set(selectedPlayIdVar -> Some(id), resultsVar -> None, modalErrorVar -> None))
  }

  /** The selected row's `gameName`, looked up from the already-loaded [[playsVar]] — a history row spans every game, so
    * unlike `GameResultsPage`'s single-game modal, [[GameHeader]] needs a name resolved per selected play, and
    * `GameResults` itself (the modal's own fetch) never carries one.
    */
  private val selectedGameNameSignal: Signal[Option[String]] = {
    playsVar.signal.combineWith(selectedPlayIdVar.signal).map { case (plays, selected) =>
      for {
        id   <- selected
        rows <- plays
        row  <- rows.find(_.playId == id)
      } yield row.gameName
    }
  }

  /** Same `div.modal` + `Var[Boolean]` pattern `GameResultsPage.renderModal` uses, for the same jsdom reason. */
  private def renderModal(): HtmlElement = {
    val indexSignal = currentIdsVar.signal.combineWith(selectedPlayIdVar.signal).map { case (ids, selected) =>
      selected.map(ids.indexOf)
    }
    val atFirst     = indexSignal.map(index => index.forall(_ <= 0)).distinct
    val atLast      = {
      currentIdsVar.signal
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
      renderAnswersTable(results.answers),
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

  /** Same styling `GameResultsPage.renderOutcomeBadge`/`GameInstancePage.renderOutcomeBadge` use — kept as its own copy
    * rather than shared, matching those pages' own pattern.
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
