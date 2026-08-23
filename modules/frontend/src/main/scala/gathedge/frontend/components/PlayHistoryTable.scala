package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.MyPlaySummary
import gathedge.shared.i18n.UiKeys

/** A page of cross-game plays — `dto.MyPlayPage`'s rows — shared by `MyPlayHistoryPage`, `SharedPlayerHistoryPage` and
  * the admin games tab. Every row links to the game itself, which is always public (`GameEndpoints.get`).
  *
  * `onView` and `onPlayAgain` both default off — only `MyPlayHistoryPage` turns them on. Both need an async round trip
  * with error handling, which only the owning page can host, the same reasoning for both: viewing needs
  * `GameApiClient.getResults`, which only authorizes the play's own player (`GameService.requireOwnedPlay`), not an
  * observer; replaying needs `GameReplay.start` (a `GameApiClient.get` + `startPlay` pair) and only makes sense as a
  * shortcut for the reader's own history. `SharedPlayerHistoryPage` and the admin games tab render the same rows
  * without either.
  */
object PlayHistoryTable {

  def render(
    rows: Signal[List[MyPlaySummary]],
    onView: Option[Observer[Long]] = None,
    onPlayAgain: Option[Observer[MyPlaySummary]] = None,
  ): HtmlElement = {
    val showActions = onView.isDefined || onPlayAgain.isDefined
    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table table-sm",
        thead(
          tr(
            th(I18n.t(UiKeys.myPlaysGameCol)),
            th(I18n.t(UiKeys.myPlaysScoreCol)),
            th(I18n.t(UiKeys.myPlaysWordsCol)),
            th(I18n.t(UiKeys.gameResultsVariantCol)),
            th(I18n.t(UiKeys.myPlaysStartedCol)),
            if (showActions) th() else emptyNode,
          )
        ),
        tbody(children <-- rows.map(_.map(renderRow(_, onView, onPlayAgain)))),
      ),
    )
  }

  def maybeEmpty(rows: Signal[List[MyPlaySummary]]): Signal[Option[HtmlElement]] = {
    rows.map(list => Option.when(list.isEmpty)(div(cls := "text-base-content/70", I18n.t(UiKeys.myPlaysEmpty))))
  }

  private def renderRow(
    play: MyPlaySummary,
    onView: Option[Observer[Long]],
    onPlayAgain: Option[Observer[MyPlaySummary]],
  ): HtmlElement = {
    val viewButton      = onView.map { observer =>
      button(
        cls := "btn btn-ghost btn-xs",
        typ := "button",
        I18n.t(UiKeys.gameResultsViewButton),
        onClick.mapTo(play.playId) --> observer,
      )
    }
    val playAgainButton = onPlayAgain.map { observer =>
      button(
        cls := "btn btn-ghost btn-xs",
        typ := "button",
        I18n.t(UiKeys.gameInstancePlayAgain),
        onClick.mapTo(play) --> observer,
      )
    }
    tr(
      cls := "hover",
      td(a(cls := "link link-hover", AppRouter.router.navigateTo(Page.GameInstance(play.gameSlug)), play.gameName)),
      td(s"${play.score} / ${play.maxScore}"),
      td(play.wordCount.toString),
      td(Labels.variant(play.variant)),
      td(Formats.dateTime(play.startedAt)),
      if (onView.isDefined || onPlayAgain.isDefined)
        td(cls := "flex gap-1", viewButton.getOrElse(emptyNode), playAgainButton.getOrElse(emptyNode))
      else
        emptyNode,
    )
  }
}
