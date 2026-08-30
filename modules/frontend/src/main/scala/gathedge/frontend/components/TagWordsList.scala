package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.GameSetupWord
import gathedge.shared.i18n.UiKeys

/** The eligible-word preview — source word in one column, its marked accepted translation(s) in the other — shared by
  * `GameSetupPage` (picking a quiz's tags/languages) and `GameInstancePage` (picking a play's direction/preference for
  * an existing quiz). Both pages fetch the same `GameSetupWord` list and drive it through this one table so the study
  * preview reads identically wherever it is shown.
  */
object TagWordsList {

  /** `collapsed` hides the list behind a "Show words" daisyUI collapse (`GameInstancePage`'s play screen, where the
    * list would otherwise spoil the pool before play starts) instead of showing it inline (`GameSetupPage`, where the
    * player is still picking which words to include).
    */
  def render(words: Signal[List[GameSetupWord]], loading: Signal[Boolean], collapsed: Boolean = false): HtmlElement = {
    val content = div(
      cls := "flex flex-col gap-2",
      span(cls := "label-text text-xs", I18n.t(UiKeys.tagWordsListHeading)),
      span(
        cls    := "label-text text-sm opacity-70",
        child.text <-- words.map(list => I18n.plural(UiKeys.tagWordsListCount, list.size.toLong)),
      ),
      div(
        cls    := "mt-1 max-h-96 overflow-y-auto border border-base-300 rounded",
        table(
          cls := "table table-sm",
          tbody(children <-- words.map(_.map(renderRow))),
        ),
      ),
      child.maybe <-- words.combineWith(loading).map { case (list, isLoading) =>
        Option.when(list.isEmpty && !isLoading)(
          p(cls := "text-sm opacity-60", I18n.t(UiKeys.tagWordsListEmpty))
        )
      },
    )
    if (collapsed) {
      detailsTag(
        cls := "collapse collapse-arrow border border-base-300 rounded-box",
        summaryTag(cls := "collapse-title text-sm font-medium", I18n.t(UiKeys.tagWordsListToggle)),
        div(cls        := "collapse-content", content),
      )
    } else {
      content
    }
  }

  /** One row: the source word with its part of speech (two rows spelled alike are two different words), plus its marked
    * accepted translation(s) so the player can study the pool before playing — see `GameSetupWord.translations`. Plain
    * comma-joined text, not `WordCollect.renderChip`: that chip toggles a mark against the *collect* tag, which has no
    * place on this read-only preview.
    */
  private def renderRow(word: GameSetupWord): HtmlElement = {
    tr(
      td(
        cls  := "text-sm",
        div(word.text),
        word.partOfSpeech.map(pos => div(cls := "text-xs opacity-60", Labels.partOfSpeech(pos))),
      ),
      td(cls := "text-xs opacity-60", word.translations.mkString(", ")),
    )
  }
}
