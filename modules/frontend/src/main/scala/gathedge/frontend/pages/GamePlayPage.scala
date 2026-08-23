package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.ReactiveHtmlElement
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient, GameReplay}
import gathedge.frontend.components.{Alert, AppShell, GameHeader, GuestBanner, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.{AppState, PendingPlay, PlayHandoff}
import gathedge.shared.domain.{AnswerOutcome, Gender, WordLanguage}
import gathedge.shared.dto.{GameAnswerResult, GamePrompt, GameResults, GameVariantDto}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

/** One attempt at a game: prompt, answer, next, until the play finishes — see `Page.GamePlay`'s doc comment for why
  * this is a separate route from `GameInstancePage`'s picker rather than a phase within it. Shows a read-only
  * [[GameHeader]] (name + played variant) above the phase content, but carries none of the picker's owner chrome
  * (rename/share/QR/tags) — that stays on the picker page, which is always reachable via the finished screen's "Back to
  * games" or a fresh "Play again".
  *
  * One prompt is shown at a time, with a "3 of 12" progress line and nothing else about how the game is going —
  * `GameEndpoints.submitAnswer`'s doc comment is why: a player is never shown correctness mid-game, and that includes
  * the running score. Once the play is finished, the score and the full answer history become the point of the screen —
  * that is what `getResults` and `Phase.Finished` are for.
  */
object GamePlayPage {

  def render(slug: String, playId: Long): HtmlElement = {
    AppShell.render(Page.GamePlay(slug, playId), new GamePlayPage(slug, playId).render())
  }
}

private class GamePlayPage(slug: String, playId: Long) {

  private sealed trait Phase

  private object Phase {
    case object Loading extends Phase

    final case class Playing(prompt: GamePrompt) extends Phase

    case object Finished extends Phase
  }

  /** Read once, synchronously, at construction — see `PendingPlay`'s doc comment. `None` means this mount has nothing
    * to resume (a raw URL visit, refresh, or back/forward navigation, the hand-off being one-shot and in-memory), so
    * [[render]] bounces straight back to the picker instead of building the loop at all.
    */
  private val handoff = PendingPlay.take(playId)

  private val promptVar   = Var(Option.empty[GamePrompt])
  private val finishedVar = Var(false)
  private val resultsVar  = Var(Option.empty[GameResults])

  private val answerTextVar = Var("")
  private val submittingVar = Var(false)
  private val startingVar   = Var(false)

  private val errorVar: Var[Option[String]] = Var(None)

  private val nextBus      = new EventBus[Unit]()
  private val submitBus    = new EventBus[Unit]()
  private val resultsBus   = new EventBus[Unit]()
  private val playAgainBus = new EventBus[Unit]()

  private val phaseSignal: Signal[Phase] = {
    finishedVar.signal
      .combineWith(promptVar.signal)
      .map {
        case (true, _)        => Phase.Finished
        case (false, Some(p)) => Phase.Playing(p)
        case (false, None)    => Phase.Loading
      }
      .distinct
  }

  def render(): HtmlElement = {
    handoff match {
      case None            =>
        div(onMountCallback(_ => AppRouter.router.pushState(Page.GameInstance(slug))))
      case Some(playState) =>
        renderPlay(playState)
    }
  }

  private def renderPlay(playState: PlayHandoff): HtmlElement = {
    div(
      cls := "max-w-xl mx-auto",
      Alert.maybeError(errorVar.signal),
      // Persists through the whole play, not just the picker: `startPlay`'s own guest detour is what usually mints
      // the account this page's very first mount is riding on, so a reader landing here for the first time is the
      // reader most likely to need this notice.
      child.maybe <-- AppState.currentUserSignal.map(user => Option.when(user.exists(_.isGuest))(GuestBanner.render())),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          GameHeader.render(playState.gameName, playState.variant),
          child <-- phaseSignal.map(renderPhase(_, playState)),
        ),
      ),
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
      playAgainBus.events
        .map(_ => resultsVar.now())
        .collect { case Some(results) => results.variant }
        .flatMapSwitch { variant =>
          startingVar.set(true)
          GameReplay.start(slug, variant)
        } -->
        Observer[Either[ApiError, GameReplay.Started]] {
          case Right(result) =>
            PendingPlay.set(result.playId, PlayHandoff(result.gameName, result.wordCount, result.variant))
            AppRouter.router.pushState(Page.GamePlay(result.slug, result.playId))
          case Left(err)     =>
            Var.set(startingVar -> false, errorVar -> Some(err.message))
        },
      // Last, like every other page's initial load — the stream this triggers (`nextBus`) has to already have a
      // subscriber when this fires, or the mount's own request is emitted to nobody and silently lost.
      onMountCallback(_ => nextBus.emit(())),
    )
  }

  private def nextPromptStream: EventStream[Either[ApiError, GamePrompt]] = {
    nextBus.events.flatMapSwitch(_ => GameApiClient.nextPrompt(playId))
  }

  private def submitStream: EventStream[Either[ApiError, Unit]] = {
    submitBus.events
      .filterWith(submittingVar.signal.not)
      .map(_ => (promptVar.now().flatMap(_.wordId), answerTextVar.now().trim))
      .collect { case (Some(wordId), text) if text.nonEmpty => (wordId, text) }
      .flatMapSwitch { case (wordId, text) =>
        submittingVar.set(true)
        GameApiClient.submitAnswer(playId, wordId, text)
      }
  }

  private def resultsStream: EventStream[Either[ApiError, GameResults]] = {
    resultsBus.events.flatMapSwitch(_ => GameApiClient.getResults(playId))
  }

  private def renderPhase(phase: Phase, playState: PlayHandoff): HtmlElement = {
    phase match {
      case Phase.Loading         =>
        span(cls := "loading loading-spinner")
      case Phase.Playing(prompt) =>
        renderPrompt(prompt, playState)
      case Phase.Finished        =>
        renderFinished()
    }
  }

  private def renderPrompt(prompt: GamePrompt, playState: PlayHandoff): HtmlElement = {
    val answerInput = input(
      cls         := "input input-sm w-full",
      placeholder := I18n.t(UiKeys.gameInstanceAnswerPlaceholder),
      controlled(value <-- answerTextVar.signal, onInput.mapToValue --> answerTextVar.writer),
      // `renderPrompt` is freshly mounted for every new `GamePrompt` (see `phaseSignal`'s `.distinct`), so
      // focusing on mount both auto-focuses on first load and re-focuses on every new word.
      onMountFocus,
    )
    div(
      p(
        cls        := "text-sm opacity-70",
        I18n.t(UiKeys.gameInstanceProgress, prompt.position.getOrElse(0), playState.wordCount),
      ),
      h2(cls       := "text-xl font-semibold my-2", prompt.wordText.getOrElse("")),
      form(
        cls        := "flex flex-wrap items-end gap-2",
        noValidate := true,
        onSubmit.preventDefault.mapToUnit --> submitBus.writer,
        label(
          cls := "form-control grow",
          span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstanceAnswerLabel)),
          if (showGenderPicker(playState.variant)) renderGenderPicker(answerInput) else emptyNode,
          answerInput,
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

  /** `variant.targetLanguage` is already the resolved (post-swap) answer language for this play, so no separate
    * direction lookup is needed here the way `GameInstancePage.startBus`'s handler needs one at `startPlay` time.
    */
  private def showGenderPicker(variant: GameVariantDto): Boolean = {
    variant.includeDefiniteArticles && variant.targetLanguage == WordLanguage.De
  }

  /** A daisyUI `join` of btn-styled radio inputs for the German article, mirroring `BulkUploadDialog`'s
    * `renderLanguageRadio` pattern — one click sets the article prefix instead of typing it. Picking one replaces any
    * article already at the front of [[answerTextVar]] and refocuses `answerInput` so the player can keep typing the
    * word straight after it.
    */
  private def renderGenderPicker(answerInput: ReactiveHtmlElement[dom.html.Input]): HtmlElement = {
    div(
      cls := "join mb-1",
      Gender.all.map(gender => renderGenderRadio(gender, answerInput)),
    )
  }

  private def renderGenderRadio(gender: Gender, answerInput: ReactiveHtmlElement[dom.html.Input]): HtmlElement = {
    val article        = Gender.article(gender)
    input(
      typ        := "radio",
      cls        := "join-item btn btn-xs",
      nameAttr   := "answer-gender",
      aria.label := article,
      controlled(
        checked <-- answerTextVar.signal.map(_.toLowerCase.startsWith(article + " ")),
        onClick.mapToUnit --> Observer[Unit] { _ =>
          answerTextVar.set(s"$article ${stripArticle(answerTextVar.now())}")
          answerInput.ref.focus()
        },
      ),
    )
  }

  /** Drops a leading `der `/`die `/`das ` from a typed or picked answer, so picking a different article replaces the
    * old one instead of stacking in front of it.
    */
  private def stripArticle(text: String): String = {
    val lower = text.toLowerCase
    Gender.all.map(Gender.article).find(a => lower.startsWith(a + " ")).fold(text)(a => text.drop(a.length + 1))
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
          onClick.mapToUnit --> playAgainBus.writer,
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
