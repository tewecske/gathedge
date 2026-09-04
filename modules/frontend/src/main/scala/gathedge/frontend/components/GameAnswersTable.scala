package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.AnswerOutcome
import gathedge.shared.dto.GameAnswerResult
import gathedge.shared.i18n.UiKeys

/** The finished-play answer history — source word, every accepted translation, what the player typed, and the outcome
  * badge — shown by all four screens that display one: the player's end-of-play screen (`GamePlayPage`), their own
  * history modal (`MyPlayHistoryPage`), the game owner's per-play modal (`GameResultsPage`), and an administrator's
  * view of a player's history (`AdminUserPlaysPage`). One table so the four never drift apart.
  *
  * The "expected" column joins `GameAnswerResult.expectedTexts` — a word can have more than one accepted translation,
  * or sit under more than one of the game's tags — so a player sees every answer that would have scored, not just the
  * one row the server happened to grade against.
  */
object GameAnswersTable {

  def render(answers: List[GameAnswerResult]): HtmlElement = {
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
        tbody(answers.map(renderRow)),
      ),
    )
  }

  /** The word cell carries the part of speech under the word: `words` is unique on
    * `(language, text_norm, part_of_speech, gender)`, so two rows spelled alike are two different words, and a history
    * listing two rows reading `run` is only readable with it.
    */
  private def renderRow(answer: GameAnswerResult): HtmlElement = {
    tr(
      cls := "hover",
      td(
        div(answer.wordText),
        answer.partOfSpeech.map(pos => div(cls := "text-xs opacity-60", Labels.partOfSpeech(pos))),
      ),
      td(answer.expectedTexts.mkString(", ")),
      td(answer.givenText),
      td(outcomeBadge(answer.outcome)),
    )
  }

  /** Mistakes (typo/wrong) get a warning/error badge, matching `AdminUserDiagnostics.renderOutcome`'s style for
    * `login_attempts.outcome` — the same "outcome of one attempt, in a table" shape.
    *
    * Public because `GamePlayPage`'s mid-play feedback badges one answer with no table around it, and an answer must
    * not read one way between two words and another way in the history.
    */
  def outcomeBadge(outcome: AnswerOutcome): HtmlElement = {
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
