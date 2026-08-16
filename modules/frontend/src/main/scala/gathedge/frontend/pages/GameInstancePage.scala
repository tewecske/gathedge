package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiClient, ApiError, GameApiClient}
import gathedge.frontend.components.{Alert, AppShell, GuestBanner, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.{AppState, GameOwnership}
import gathedge.shared.domain.{AnswerOutcome, User}
import gathedge.shared.dto.{GameAnswerResult, GameDetail, GamePrompt, GameResults, PlayStarted}
import gathedge.shared.i18n.UiKeys

/** One quiz, played from its shared link (`/g/{slug}`).
  *
  * The initial `GET /api/games/{slug}` mints nobody — see `Page.GameInstance`'s doc comment — a visitor can read the
  * quiz's name and tags with no session at all. Starting a play is the first write the page makes, so it is where the
  * guest detour sits ([[asReader]], copied in spirit from `GameSetupPage`'s), and it sits there once: every later call
  * in the loop (`nextPrompt`, `submitAnswer`) runs against the session `startPlay` already established.
  *
  * One prompt is shown at a time, with a "3 of 12" progress line and nothing else about how the game is going —
  * `GameEndpoints.submitAnswer`'s doc comment is why: a player is never shown correctness mid-game, and that includes
  * the running score. Once the play is finished, the score and the full answer history become the point of the screen —
  * that is what `getResults` and the phase's `Finished` state are for.
  */
object GameInstancePage {

  def render(slug: String): HtmlElement = {
    AppShell.render(Page.GameInstance(slug), new GameInstancePage(slug).render())
  }
}

private class GameInstancePage(slug: String) {

  private sealed trait Phase

  private object Phase {
    case object NotStarted extends Phase
    case object Loading    extends Phase

    final case class Playing(prompt: GamePrompt, wordCount: Int) extends Phase

    case object Finished extends Phase
  }

  private val gameVar    = Var(Option.empty[GameDetail])
  private val missingVar = Var(false)

  /** The name shown in the header, separate from [[gameVar]] so a rename updates it without rebuilding the card — see
    * [[render]]'s comment on why the card itself is built once.
    */
  private val nameVar = Var("")

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)

  private val playIdVar    = Var(Option.empty[Long])
  private val wordCountVar = Var(0)

  private val promptVar   = Var(Option.empty[GamePrompt])
  private val finishedVar = Var(false)

  private val resultsVar = Var(Option.empty[GameResults])

  private val answerTextVar = Var("")

  private val startingVar   = Var(false)
  private val submittingVar = Var(false)

  /** Whether this browser is the one that created the game — see `GameOwnership`'s doc comment on why that is a local
    * hint rather than something `GameDetail` states. A `Var` rather than a `val` so a 403 on rename (a stale hint, or
    * the same account signed in elsewhere) can turn the control off without a reload.
    */
  private val isOwnerVar = Var(GameOwnership.isOwned(slug))

  private val renameEditingVar                    = Var(false)
  private val renameTextVar                       = Var("")
  private val renameSubmittingVar                 = Var(false)
  private val renameErrorVar: Var[Option[String]] = Var(None)

  /** Mirrors who the reader is at the moment a request is made — same trick as `GameSetupPage.readerVar`, needed
    * because [[asReader]] reads it outside a subscription.
    */
  private val readerVar = Var(Option.empty[User])

  private val loadBus    = new EventBus[Unit]()
  private val startBus   = new EventBus[Unit]()
  private val nextBus    = new EventBus[Unit]()
  private val submitBus  = new EventBus[Unit]()
  private val resultsBus = new EventBus[Unit]()

  private val renameEditBus   = new EventBus[Unit]()
  private val renameCancelBus = new EventBus[Unit]()
  private val renameSaveBus   = new EventBus[Unit]()

  private val phaseSignal: Signal[Phase] = {
    playIdVar.signal
      .combineWith(finishedVar.signal, promptVar.signal, wordCountVar.signal)
      .map {
        case (None, _, _, _)              =>
          Phase.NotStarted
        case (Some(_), true, _, _)        =>
          Phase.Finished
        case (Some(_), false, Some(p), n) =>
          Phase.Playing(p, n)
        case (Some(_), false, None, _)    =>
          Phase.Loading
      }
      .distinct
  }

  /** A per-account write, with the guest detour in front of it — copied from `GameSetupPage.asReader`. With no session
    * `startPlay` cannot succeed, so a guest is minted first and the call retried against the session that creates.
    * Signed in, the mint is skipped entirely.
    */
  private def asReader[A](write: () => EventStream[Either[ApiError, A]]): EventStream[Either[ApiError, A]] = {
    readerVar.now() match {
      case Some(_) =>
        write()
      case None    =>
        ApiClient.createGuest.flatMapSwitch {
          case Right(response) =>
            AppState.setUser(response.user)
            noticeVar.set(Some(I18n.t(UiKeys.guestBannerHint)))
            write()
          case Left(err)       =>
            EventStream.fromValue(Left(err))
        }
    }
  }

  def render(): HtmlElement = {
    div(
      cls := "max-w-xl mx-auto",
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(noticeVar.signal),
      child.maybe <-- missingVar.signal.map(Option.when(_)(Alert.info(I18n.t(UiKeys.gameInstanceNotFound)))),
      // Built once, the moment the game finishes loading, and never again — see `renderGameCard`'s doc comment. A
      // rename only ever writes through `nameVar`, so it cannot remount the phase in progress underneath it.
      child.maybe <-- gameVar.signal
        .map(_.isDefined)
        .distinct
        .map(loaded => Option.when(loaded)(renderGameCard())),
      child.maybe <-- AppState.currentUserSignal.map(user => Option.when(user.exists(_.isGuest))(GuestBanner.render())),
      onMountCallback(_ => loadBus.emit(())),
      AppState.currentUserSignal --> readerVar.writer,
      loadBus.events.flatMapSwitch(_ => GameApiClient.get(slug)) -->
        Observer[Either[ApiError, GameDetail]] {
          case Right(detail) =>
            Var.set(gameVar -> Some(detail), nameVar -> detail.name, missingVar -> false, errorVar -> None)
          case Left(err)     =>
            // A quiz that is not there is a different thing from a request that failed, and reads differently.
            if (err.status == 404)
              Var.set(missingVar -> true, errorVar -> None)
            else
              errorVar.set(Some(err.message))
        },
      startBus.events -->
        Observer[Unit] { _ =>
          // Also clears the previous play's finish state, so this doubles as "Play again" — see `renderResults`.
          Var.set(
            startingVar -> true,
            errorVar    -> None,
            finishedVar -> false,
            resultsVar  -> None,
            promptVar   -> None,
          )
        },
      startBus.events.flatMapSwitch(_ => asReader(() => GameApiClient.startPlay(slug))) -->
        Observer[Either[ApiError, PlayStarted]] {
          case Right(started) =>
            Var.set(
              playIdVar    -> Some(started.playId),
              wordCountVar -> started.wordCount,
              startingVar  -> false,
            )
            nextBus.emit(())
          case Left(err)      =>
            Var.set(startingVar -> false, errorVar -> Some(err.message))
        },
      nextPromptStream --> Observer[Either[ApiError, GamePrompt]] {
        case Right(prompt) =>
          if (prompt.finished) {
            Var.set(finishedVar -> true, promptVar -> None, submittingVar -> false)
            resultsBus.emit(())
          } else {
            Var.set(promptVar -> Some(prompt), answerTextVar -> "", submittingVar -> false)
          }
        case Left(err)     =>
          Var.set(submittingVar -> false, errorVar -> Some(err.message))
      },
      submitStream --> Observer[Either[ApiError, Unit]] {
        case Right(_)  =>
          nextBus.emit(())
        case Left(err) =>
          Var.set(submittingVar -> false, errorVar -> Some(err.message))
      },
      resultsStream --> Observer[Either[ApiError, GameResults]] {
        case Right(results) =>
          resultsVar.set(Some(results))
        case Left(err)      =>
          errorVar.set(Some(err.message))
      },
      renameEditBus.events -->
        Observer[Unit](_ => Var.set(renameTextVar -> nameVar.now(), renameEditingVar -> true, renameErrorVar -> None)),
      renameCancelBus.events -->
        Observer[Unit](_ => Var.set(renameEditingVar -> false, renameErrorVar -> None)),
      renameStream --> Observer[Either[ApiError, GameDetail]] {
        case Right(detail) =>
          Var.set(
            nameVar             -> detail.name,
            gameVar             -> Some(detail),
            renameEditingVar    -> false,
            renameSubmittingVar -> false,
            renameErrorVar      -> None,
          )
        case Left(err)     =>
          if (err.status == 403) {
            // A stale local hint, or the same account signed in elsewhere and the game changed owner-relevant state
            // since: either way, this browser does not get to keep offering a control that will not work.
            GameOwnership.forget(slug)
            Var.set(
              isOwnerVar          -> false,
              renameEditingVar    -> false,
              renameSubmittingVar -> false,
              errorVar            -> Some(err.message),
            )
          } else {
            Var.set(
              renameSubmittingVar -> false,
              renameErrorVar      -> Some(err.fieldErrors.getOrElse("name", err.message)),
            )
          }
      },
    )
  }

  private def nextPromptStream: EventStream[Either[ApiError, GamePrompt]] = {
    nextBus.events
      .map(_ => playIdVar.now())
      .collect { case Some(playId) => playId }
      .flatMapSwitch(playId => GameApiClient.nextPrompt(playId))
  }

  private def submitStream: EventStream[Either[ApiError, Unit]] = {
    submitBus.events
      .filterWith(submittingVar.signal.not)
      .map(_ => (playIdVar.now(), promptVar.now().flatMap(_.wordId), answerTextVar.now().trim))
      .collect { case (Some(playId), Some(wordId), text) if text.nonEmpty => (playId, wordId, text) }
      .flatMapSwitch { case (playId, wordId, text) =>
        submittingVar.set(true)
        GameApiClient.submitAnswer(playId, wordId, text)
      }
  }

  private def resultsStream: EventStream[Either[ApiError, GameResults]] = {
    resultsBus.events
      .map(_ => playIdVar.now())
      .collect { case Some(playId) => playId }
      .flatMapSwitch(playId => GameApiClient.getResults(playId))
  }

  private def renameStream: EventStream[Either[ApiError, GameDetail]] = {
    renameSaveBus.events
      .filterWith(renameSubmittingVar.signal.not)
      .map(_ => renameTextVar.now().trim)
      .collect { case text if text.nonEmpty => text }
      .flatMapSwitch { text =>
        renameSubmittingVar.set(true)
        GameApiClient.rename(slug, text)
      }
  }

  /** The card's chrome — name, language pair, tags — built exactly once, the first time [[render]] sees the game
    * loaded. It reads [[gameVar]] with `.now()` rather than reactively: those fields never change after load (a rename
    * only ever touches [[nameVar]]), and rebuilding this on every signal tick would remount whatever `phaseSignal` is
    * showing underneath it — losing an in-progress answer to a rename made mid-play.
    */
  private def renderGameCard(): HtmlElement = {
    val detail = gameVar
      .now()
      .getOrElse(
        throw new IllegalStateException("renderGameCard called before the game finished loading")
      )
    div(
      cls := "card bg-base-100 shadow mt-4",
      div(
        cls := "card-body",
        renderNameHeader(),
        p(
          cls   := "text-sm opacity-70",
          s"${Labels.language(detail.sourceLanguage)} → ${Labels.language(detail.targetLanguage)}",
        ),
        if (detail.tagNames.nonEmpty)
          div(cls := "flex flex-wrap gap-2 mt-1", detail.tagNames.map(name => span(cls := "badge badge-ghost", name)))
        else
          emptyNode,
        div(cls := "mt-4", child <-- phaseSignal.map(renderPhase)),
      ),
    )
  }

  private def renderNameHeader(): HtmlElement = {
    div(
      child <-- renameEditingVar.signal.map {
        case true  =>
          renderRenameForm()
        case false =>
          renderNameDisplay()
      }
    )
  }

  private def renderNameDisplay(): HtmlElement = {
    div(
      cls := "flex items-center gap-2 flex-wrap",
      h1(cls := "card-title text-2xl", child.text <-- nameVar.signal),
      child.maybe <-- isOwnerVar.signal.map { owner =>
        Option.when(owner)(
          button(
            cls   := "btn btn-ghost btn-xs",
            typ   := "button",
            title := I18n.t(UiKeys.gameInstanceRenameEdit),
            "✎",
            onClick.mapToUnit --> renameEditBus.writer,
          )
        )
      },
    )
  }

  private def renderRenameForm(): HtmlElement = {
    div(
      form(
        cls        := "flex items-center gap-2 flex-wrap",
        noValidate := true,
        onSubmit.preventDefault.mapToUnit --> renameSaveBus.writer,
        label(
          cls         := "sr-only",
          forId       := "gameInstanceRenameInput",
          I18n.t(UiKeys.gameInstanceRenameLabel),
        ),
        input(
          idAttr      := "gameInstanceRenameInput",
          cls         := "input input-sm",
          placeholder := I18n.t(UiKeys.gameInstanceRenameLabel),
          controlled(value <-- renameTextVar.signal, onInput.mapToValue --> renameTextVar.writer),
        ),
        button(
          cls         := "btn btn-sm btn-primary",
          typ         := "submit",
          disabled <-- renameSubmittingVar.signal,
          I18n.t(UiKeys.commonSave),
        ),
        button(
          cls         := "btn btn-sm btn-ghost",
          typ         := "button",
          disabled <-- renameSubmittingVar.signal,
          I18n.t(UiKeys.commonCancel),
          onClick.mapToUnit --> renameCancelBus.writer,
        ),
      ),
      child.maybe <-- renameErrorVar.signal.map(_.map(msg => p(cls := "text-error text-xs mt-1", msg))),
    )
  }

  private def renderPhase(phase: Phase): HtmlElement = {
    phase match {
      case Phase.NotStarted          =>
        renderStart()
      case Phase.Loading             =>
        span(cls := "loading loading-spinner")
      case Phase.Playing(prompt, wc) =>
        renderPrompt(prompt, wc)
      case Phase.Finished            =>
        renderFinished()
    }
  }

  private def renderStart(): HtmlElement = {
    button(
      cls := "btn btn-primary",
      typ := "button",
      disabled <-- startingVar.signal,
      I18n.t(UiKeys.gameInstanceStart),
      onClick.mapToUnit --> startBus.writer,
    )
  }

  private def renderPrompt(prompt: GamePrompt, wordCount: Int): HtmlElement = {
    div(
      p(
        cls        := "text-sm opacity-70",
        I18n.t(UiKeys.gameInstanceProgress, prompt.position.getOrElse(0), wordCount),
      ),
      h2(cls       := "text-xl font-semibold my-2", prompt.wordText.getOrElse("")),
      form(
        cls        := "flex flex-wrap items-end gap-2",
        noValidate := true,
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        label(
          cls := "form-control grow",
          span(cls      := "label-text text-xs", I18n.t(UiKeys.gameInstanceAnswerLabel)),
          input(
            cls         := "input input-sm w-full",
            placeholder := I18n.t(UiKeys.gameInstanceAnswerPlaceholder),
            controlled(value <-- answerTextVar.signal, onInput.mapToValue --> answerTextVar.writer),
          ),
        ),
        button(
          cls := "btn btn-sm btn-primary",
          typ := "submit",
          disabled <-- submittingVar.signal,
          I18n.t(UiKeys.gameInstanceSubmit),
        ),
      ),
    )
  }

  private def renderFinished(): HtmlElement = {
    div(child <-- resultsVar.signal.map {
      case None          =>
        span(cls := "loading loading-spinner")
      case Some(results) =>
        renderResults(results)
    })
  }

  private def renderResults(results: GameResults): HtmlElement = {
    div(
      cls := "flex flex-col gap-3",
      p(cls := "font-semibold text-lg", I18n.t(UiKeys.gameInstanceFinishedTitle)),
      p(cls := "text-xl font-bold", I18n.t(UiKeys.gameInstanceScore, results.score, results.maxScore)),
      renderResultsTable(results.answers),
      div(
        cls := "flex flex-wrap items-center gap-3 mt-1",
        button(
          cls := "btn btn-primary btn-sm",
          typ := "button",
          disabled <-- startingVar.signal,
          I18n.t(UiKeys.gameInstancePlayAgain),
          onClick.mapToUnit --> startBus.writer,
        ),
        a(
          cls := "link link-hover text-sm",
          AppRouter.router.navigateTo(Page.Games),
          I18n.t(UiKeys.gameInstanceBackToGames),
        ),
      ),
    )
  }

  private def renderResultsTable(answers: List[GameAnswerResult]): HtmlElement = {
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
        tbody(answers.map(renderResultRow)),
      ),
    )
  }

  private def renderResultRow(answer: GameAnswerResult): HtmlElement = {
    tr(
      cls := "hover",
      td(answer.wordText),
      td(answer.expectedText),
      td(answer.givenText),
      td(renderOutcomeBadge(answer.outcome)),
    )
  }

  /** Mistakes (typo/wrong) get a warning/error badge, matching `AdminUserDiagnostics.renderOutcome`'s style for
    * `login_attempts.outcome` — the same "outcome of one attempt, in a table" shape.
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
