package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.components.AppShell
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.i18n.UiKeys

/** The catalog of game types (Vocabulary Quiz today), plus a link to the browsable games listing. A local `gameCard`
  * helper renders both, so adding the next game type is a call, not a reshape.
  *
  * The "All games" card shows whether or not anyone is signed in — the listing behind it is public. The "My plays" and
  * "Shared progress" cards stay signed-in only: both are personal.
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
            playLabel = I18n.t(UiKeys.gamesVocabQuizPlay),
            target = Page.GameSetup,
          ),
          // Public, like the vocabulary quiz above: the listing behind it renders for a signed-out visitor too.
          gameCard(
            title = I18n.t(UiKeys.gamesAllGamesTitle),
            body = I18n.t(UiKeys.gamesAllGamesBody),
            playLabel = I18n.t(UiKeys.gamesAllGamesOpen),
            target = Page.AllGames(),
          ),
          child.maybe <-- AppState.isSignedInSignal.map(signedIn => {
            Option.when(signedIn)(
              gameCard(
                title = I18n.t(UiKeys.gamesMyPlaysTitle),
                body = I18n.t(UiKeys.gamesMyPlaysBody),
                playLabel = I18n.t(UiKeys.gamesMyPlaysOpen),
                target = Page.MyPlays(),
              )
            )
          }),
          child.maybe <-- AppState.isSignedInSignal.map(signedIn => {
            Option.when(signedIn)(
              gameCard(
                title = I18n.t(UiKeys.gamesSharedProgressTitle),
                body = I18n.t(UiKeys.gamesSharedProgressBody),
                playLabel = I18n.t(UiKeys.gamesSharedProgressOpen),
                target = Page.SharedProgress,
              )
            )
          }),
        ),
      ),
    )
  }

  private def gameCard(title: String, body: String, playLabel: String, target: Page): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow-xl",
      div(
        cls := "card-body",
        h2(cls := "card-title", title),
        p(body),
        div(
          cls  := "card-actions justify-end",
          a(cls := "btn btn-primary", AppRouter.router.navigateTo(target), playLabel),
        ),
      ),
    )
  }
}
