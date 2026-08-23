package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.MyPlaySummary
import gathedge.shared.i18n.UiKeys

/** A page of cross-game plays — `dto.MyPlayPage`'s rows — shared by `MyPlayHistoryPage`, `SharedPlayerHistoryPage` and
  * the admin games tab. Every row links to the game itself, which is always public (`GameEndpoints.get`).
  *
  * Only `onView` supplies a per-play detail column — only `MyPlayHistoryPage` passes one, since the underlying
  * `GameApiClient.getResults` only authorizes the play's own player (`GameService.requireOwnedPlay`), not an observer.
  * `SharedPlayerHistoryPage` and the admin games tab render the same rows without it.
  */
object PlayHistoryTable {

  def render(rows: Signal[List[MyPlaySummary]], onView: Option[Observer[Long]] = None): HtmlElement = {
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
            onView.map(_ => th()).getOrElse(emptyNode),
          )
        ),
        tbody(children <-- rows.map(_.map(renderRow(_, onView)))),
      ),
    )
  }

  def maybeEmpty(rows: Signal[List[MyPlaySummary]]): Signal[Option[HtmlElement]] = {
    rows.map(list => Option.when(list.isEmpty)(div(cls := "text-base-content/70", I18n.t(UiKeys.myPlaysEmpty))))
  }

  private def renderRow(play: MyPlaySummary, onView: Option[Observer[Long]]): HtmlElement = {
    tr(
      cls := "hover",
      td(a(cls := "link link-hover", AppRouter.router.navigateTo(Page.GameInstance(play.gameSlug)), play.gameName)),
      td(s"${play.score} / ${play.maxScore}"),
      td(play.wordCount.toString),
      td(Labels.variant(play.variant)),
      td(Formats.dateTime(play.startedAt)),
      onView
        .map { observer =>
          td(
            button(
              cls := "btn btn-ghost btn-xs",
              typ := "button",
              I18n.t(UiKeys.gameResultsViewButton),
              onClick.mapTo(play.playId) --> observer,
            )
          )
        }
        .getOrElse(emptyNode),
    )
  }
}
