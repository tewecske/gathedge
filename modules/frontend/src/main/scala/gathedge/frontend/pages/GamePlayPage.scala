package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.ReactiveHtmlElement
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient, GameReplay}
import gathedge.frontend.components.{Alert, AppShell, ArticlePicker, GameAnswersTable, GameHeader, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.{AppState, PendingPlay, PlayHandoff}
import gathedge.shared.domain.{AnswerOutcome, GameMode, GameScoring, LanguageProfile}
import gathedge.shared.dto.{GameAnswerResult, GamePrompt, GameResults, GameVariantDto}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

/** One attempt at a game: prompt, answer, next, until the play finishes — see `Page.GamePlay`'s doc comment for why
  * this is a separate route from `GameInstancePage`'s picker rather than a phase within it. Shows a read-only
  * [[GameHeader]] (name + played variant) above the phase content, but carries none of the picker's owner chrome
  * (rename/share/QR/tags) — that stays on the picker page, which is always reachable via the finished screen's "Back to
  * games" or a fresh "Play again".
  *
  * One prompt is shown at a time, with a "3 of 12" progress line. Each answer is graded on the spot: `submitAnswer`
  * answers with the row it recorded, [[Phase.Feedback]] shows it beside the same word, and the play moves on by itself
  * after a short hold — longer for a mistake, since a mistake has an accepted answer to read. The running score is
  * still withheld until the end; only this one word's outcome is shown. Once the play is finished, the score and the
  * full answer history become the point of the screen — that is what `getResults` and `Phase.Finished` are for.
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

    /** The same word still on screen, now with its grade under it and no way to answer it again. Left by
      * [[advanceBus]], whichever fired it: the hold timer, the "Next" button, or a second Enter.
      */
    final case class Feedback(prompt: GamePrompt, result: GameAnswerResult) extends Phase

    case object Finished extends Phase
  }

  /** How long [[Phase.Feedback]] holds before the play moves on by itself. A correct answer is a glance; a mistake
    * carries an accepted answer to read, so it is held long enough to read it. Either is skipped by Enter or "Next".
    */
  private val correctHoldMs = 1500
  private val mistakeHoldMs = 4000

  /** Read once, synchronously, at construction — see `PendingPlay`'s doc comment. `None` means this mount has nothing
    * to resume (a raw URL visit, refresh, or back/forward navigation, the hand-off being one-shot and in-memory), so
    * [[render]] bounces straight back to the picker instead of building the loop at all.
    */
  private val handoff = PendingPlay.take(playId)

  private val promptVar   = Var(Option.empty[GamePrompt])
  private val finishedVar = Var(false)
  private val resultsVar  = Var(Option.empty[GameResults])

  /** The graded row for the word on screen, or `None` while it is still being answered. */
  private val feedbackVar = Var(Option.empty[GameAnswerResult])

  private val answerTextVar = Var("")
  private val submittingVar = Var(false)
  private val startingVar   = Var(false)

  private val errorVar: Var[Option[String]] = Var(None)

  private val nextBus = new EventBus[Unit]()

  /** Leaves [[Phase.Feedback]] for the next word. Three things write to it and they mean the same thing: the hold timer
    * running out, the "Next" button, and a second Enter (which the focused button turns into a click).
    */
  private val advanceBus = new EventBus[Unit]()

  /** The answer being sent, whatever produced it: the typed form's submit, or a clicked option in a
    * [[GameMode.MultipleChoice]] play. Carrying the text itself rather than a bare tick is what lets one stream serve
    * both — a click has its answer in hand and never touches [[answerTextVar]].
    */
  private val submitBus    = new EventBus[String]()
  private val resultsBus   = new EventBus[Unit]()
  private val playAgainBus = new EventBus[Unit]()

  private val phaseSignal: Signal[Phase] = {
    finishedVar.signal
      .combineWith(promptVar.signal, feedbackVar.signal)
      .map {
        case (true, _, _)                   => Phase.Finished
        case (false, Some(p), Some(result)) => Phase.Feedback(p, result)
        case (false, Some(p), None)         => Phase.Playing(p)
        case (false, None, _)               => Phase.Loading
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
      // `submittingVar` deliberately stays true from here until the next prompt lands: the answer form is off screen
      // for the whole hold, and a stray Enter must not send the same word twice on the way back.
      submitStream --> Observer[Either[ApiError, GameAnswerResult]] {
        case Right(result) =>
          feedbackVar.set(Some(result))
        case Left(err)     =>
          Var.set(submittingVar -> false, errorVar -> Some(err.message))
      },
      // The hold. `flatMapSwitch` over the `None` case is what cancels a pending timer the moment the reader skips
      // ahead, so a 4-second timer from a mistake can never cut the next word's feedback short.
      feedbackVar.signal.updates.flatMapSwitch {
        case Some(result) =>
          EventStream.delay(holdMsFor(result.outcome))
        case None         =>
          EventStream.empty
      } --> advanceBus.writer,
      advanceBus.events.filterWith(feedbackVar.signal.map(_.isDefined)) --> Observer[Unit] { _ =>
        Var.set(feedbackVar -> None, answerTextVar -> "")
        nextBus.emit(())
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

  private def holdMsFor(outcome: AnswerOutcome): Int = {
    outcome match {
      case AnswerOutcome.Correct                    =>
        correctHoldMs
      case AnswerOutcome.Typo | AnswerOutcome.Wrong =>
        mistakeHoldMs
    }
  }

  private def submitStream: EventStream[Either[ApiError, GameAnswerResult]] = {
    submitBus.events
      .filterWith(submittingVar.signal.not)
      .map(answer => (promptVar.now().flatMap(_.wordId), answer.trim))
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
      case Phase.Loading                  =>
        span(cls := "loading loading-spinner")
      case Phase.Playing(prompt)          =>
        renderPrompt(prompt, playState, renderAnswerArea(prompt, playState))
      case Phase.Feedback(prompt, result) =>
        renderPrompt(prompt, playState, renderFeedbackArea(prompt, playState, result))
      case Phase.Finished                 =>
        renderFinished()
    }
  }

  /** The word being asked, with `answerArea` under it: the form or the choices while it is unanswered, the grade once
    * it is. The word itself is rendered the same way either way, so it does not move as the two swap.
    */
  private def renderPrompt(prompt: GamePrompt, playState: PlayHandoff, answerArea: HtmlElement): HtmlElement = {
    div(
      p(
        cls  := "text-sm opacity-70",
        I18n.t(UiKeys.gameInstanceProgress, prompt.position.getOrElse(0), playState.wordCount),
      ),
      h2(cls := "text-xl font-semibold mt-2 mb-1", prompt.wordText.getOrElse("")),
      // Deliberately a sibling of the heading, not a child of it: `e2e/tests/game.spec.ts` reads the heading's own
      // text and compares it to the asked word verbatim, and a label inside it would be part of that text.
      p(
        cls  := "text-xs opacity-60 mb-2",
        prompt.partOfSpeech.map(Labels.partOfSpeech).getOrElse(""),
      ),
      answerArea,
    )
  }

  private def renderAnswerArea(prompt: GamePrompt, playState: PlayHandoff): HtmlElement = {
    playState.variant.mode match {
      case GameMode.Typing         =>
        renderTypedAnswer(playState)
      case GameMode.MultipleChoice =>
        renderChoices(prompt)
    }
  }

  /** The graded counterpart of [[renderAnswerArea]], forking on the same two modes. A typed answer leaves nothing on
    * screen to grade, so it is told what was accepted; a clicked one still has its buttons, so the grade is shown on
    * them.
    */
  private def renderFeedbackArea(
    prompt: GamePrompt,
    playState: PlayHandoff,
    result: GameAnswerResult,
  ): HtmlElement = {
    playState.variant.mode match {
      case GameMode.Typing         =>
        renderFeedback(result)
      case GameMode.MultipleChoice =>
        renderChoiceFeedback(prompt, result)
    }
  }

  /** How a typed answer was graded. The accepted answer is shown only for a mistake — a player who got it right has
    * nothing to read there, and it is the same word they typed anyway.
    *
    * Kept deliberately plain: no table (the results screen's own is what `e2e/tests/game.spec.ts` counts rows in) and
    * no `btn-outline` (that class is how the same suite counts a multiple-choice play's options).
    */
  private def renderFeedback(result: GameAnswerResult): HtmlElement = {
    div(
      cls := "flex flex-col items-start gap-2",
      div(
        cls := "flex flex-wrap items-center gap-2",
        GameAnswersTable.outcomeBadge(result.outcome),
        if (result.outcome == AnswerOutcome.Correct) {
          emptyNode
        } else {
          span(
            cls := "text-sm",
            I18n.t(UiKeys.gameInstanceFeedbackExpected, result.expectedTexts.mkString(", ")),
          )
        },
      ),
      nextButton(),
    )
  }

  /** How a clicked answer was graded: the same options, in the same order and the same place, now disabled and marked.
    * The accepted option is outlined as correct whether or not it was the one clicked, the clicked option carries a
    * check or a cross, and there is no separate "accepted answer" line — the answer is already one of the buttons on
    * screen.
    *
    * The "Next" button is the only thing added, and it is deliberately not a `btn-outline`: that class is how
    * `e2e/tests/game.spec.ts` counts a multiple-choice play's options, and it must still count four here.
    */
  private def renderChoiceFeedback(prompt: GamePrompt, result: GameAnswerResult): HtmlElement = {
    div(
      cls := "flex flex-col gap-2",
      span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstanceChooseLabel)),
      prompt.options.map(option => renderGradedChoice(option, result)),
      nextButton(),
    )
  }

  /** One graded option. A disabled daisyUI `btn` clears its own colour — both `--btn-border` and `color` — so the
    * outline is a Tailwind `border-*` utility (a later layer, so it wins) and the label sits in a span of its own,
    * whose colour beats what it would otherwise inherit from the button.
    *
    * The class names are written out rather than built from a tone: Tailwind reads these sources for literal class
    * names, so an interpolated `border-$tone` would compile with no CSS behind it.
    */
  private def renderGradedChoice(option: String, result: GameAnswerResult): HtmlElement = {
    val accepted                  = result.expectedTexts.exists(sameOption(_, option))
    val chosen                    = sameOption(result.givenText, option)
    val (borderCls, textCls)      = {
      if (accepted) {
        ("border-2 border-success", "text-success")
      } else if (chosen) {
        ("border-2 border-error", "text-error")
      } else {
        ("", "text-base-content/70")
      }
    }
    button(
      cls      := s"btn btn-outline justify-start $borderCls",
      typ      := "button",
      disabled := true,
      span(cls := textCls, option),
      if (chosen) choiceMark(result.outcome) else emptyNode,
    )
  }

  /** The check or cross on the option that was clicked. Its colour is set here rather than inherited, for the reason
    * [[renderGradedChoice]] gives, and the outcome is also spelled out for a screen reader — in the same words the
    * results table's badge uses, so an answer never reads one way mid-play and another way in the history.
    */
  private def choiceMark(outcome: AnswerOutcome): HtmlElement = {
    val correct = outcome == AnswerOutcome.Correct
    span(
      cls := (if (correct) "ml-auto text-success" else "ml-auto text-error"),
      span(cls := "sr-only", Labels.gameOutcome(outcome)),
      markIcon(correct),
    )
  }

  private def markIcon(correct: Boolean): SvgElement = {
    svg.svg(
      svg.cls            := "size-5",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "2.5",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(svg.d := (if (correct) "M5 13l4 4 10-10" else "M6 6l12 12M6 18L18 6")),
    )
  }

  /** Whether two option texts are the same answer, decided by the very rule the server graded the click with. A browser
    * comparing on `==` instead would leave an option unmarked exactly where the server saw a match.
    */
  private def sameOption(a: String, b: String): Boolean = {
    GameScoring.scoreChoice(a, b).outcome == AnswerOutcome.Correct
  }

  /** Leaves [[Phase.Feedback]] before the hold runs out. Focused on mount so a second Enter — the same key that sent
    * the answer — moves on without waiting. A focused button is activated by Enter natively, so no key listener of our
    * own is needed. `self-start` keeps it to its own width under the full-width column of options.
    */
  private def nextButton(): HtmlElement = {
    button(
      cls := "btn btn-sm btn-primary self-start",
      typ := "button",
      I18n.t(UiKeys.gameInstanceFeedbackNext),
      onClick.mapToUnit --> advanceBus.writer,
      onMountFocus,
    )
  }

  /** The clicked half of the loop: one button per option, in the order the server shuffled them. There are at most four
    * and sometimes fewer — see `GameService.optionsFor` — so nothing here assumes a count. A click sends the option's
    * own text through [[submitBus]], which is exactly what a typed answer sends, so the server grades both the same
    * way.
    */
  private def renderChoices(prompt: GamePrompt): HtmlElement = {
    div(
      cls := "flex flex-col gap-2",
      span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstanceChooseLabel)),
      prompt.options.map(option => renderChoice(option)),
    )
  }

  private def renderChoice(option: String): HtmlElement = {
    button(
      cls := "btn btn-outline justify-start",
      typ := "button",
      disabled <-- submittingVar.signal,
      option,
      onClick.mapTo(option) --> submitBus.writer,
    )
  }

  private def renderTypedAnswer(playState: PlayHandoff): HtmlElement = {
    val answerInput        = input(
      cls         := "input input-sm w-full",
      placeholder := I18n.t(UiKeys.gameInstanceAnswerPlaceholder),
      controlled(value <-- answerTextVar.signal, onInput.mapToValue --> answerTextVar.writer),
      // `renderPrompt` is freshly mounted for every new `GamePrompt` (see `phaseSignal`'s `.distinct`), so
      // focusing on mount both auto-focuses on first load and re-focuses on every new word.
      onMountFocus,
    )
    form(
      cls        := "flex flex-wrap items-end gap-2",
      noValidate := true,
      onSubmit.preventDefault.map(_ => answerTextVar.now()) --> submitBus.writer,
      label(
        cls := "form-control grow",
        span(cls := "label-text text-xs", I18n.t(UiKeys.gameInstanceAnswerLabel)),
        if (showGenderPicker(playState.variant)) renderGenderPicker(playState.variant, answerInput) else emptyNode,
        answerInput,
      ),
      button(
        cls := "btn btn-sm btn-primary",
        typ := "submit",
        disabled <-- submittingVar.signal,
        I18n.t(UiKeys.gameInstanceSubmit),
      ),
    )
  }

  /** `variant.targetLanguage` is already the resolved (post-swap) answer language for this play, so no separate
    * direction lookup is needed here the way `GameInstancePage.startBus`'s handler needs one at `startPlay` time.
    */
  private def showGenderPicker(variant: GameVariantDto): Boolean = {
    variant.mode == GameMode.Typing && variant.includeDefiniteArticles && LanguageProfile
      .of(variant.targetLanguage)
      .hasGenders
  }

  private def renderGenderPicker(
    variant: GameVariantDto,
    answerInput: ReactiveHtmlElement[dom.html.Input],
  ): HtmlElement = {
    val language = variant.targetLanguage
    div(
      cls := "mb-1",
      ArticlePicker.render("answer-gender", LanguageProfile.of(language), answerTextVar, () => answerInput.ref.focus()),
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
      GameAnswersTable.render(results.answers),
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

}
