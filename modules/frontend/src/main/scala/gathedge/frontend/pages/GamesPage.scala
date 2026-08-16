package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.components.AppShell
import gathedge.frontend.i18n.I18n
import gathedge.shared.i18n.UiKeys

/** The catalog of game types. One card today (Vocabulary Quiz); a local `gameCard` helper anticipates more, so adding
  * the next game type is a call, not a reshape.
  */
object GamesPage {

  def render(): HtmlElement = {
    AppShell.render(
      Page.Games,
      div(
        cls := "p-4",
        h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.gamesTitle)),
        div(
          cls  := "grid gap-4 max-w-2xl",
          gameCard(
            title = I18n.t(UiKeys.gamesVocabQuizTitle),
            body = I18n.t(UiKeys.gamesVocabQuizBody),
          ),
        ),
      ),
    )
  }

  private def gameCard(title: String, body: String): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow-xl",
      div(
        cls := "card-body",
        h2(cls := "card-title", title),
        p(body),
        div(
          cls  := "card-actions justify-end",
          // TODO: link to the setup page (Page.VocabQuizSetup or similar) once a later task adds it.
          button(cls := "btn btn-primary btn-disabled", I18n.t(UiKeys.gamesVocabQuizPlay)),
        ),
      ),
    )
  }
}
