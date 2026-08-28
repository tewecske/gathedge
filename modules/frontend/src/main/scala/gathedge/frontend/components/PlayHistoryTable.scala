package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.MyPlaySummary
import gathedge.shared.i18n.UiKeys

/** A read-only page of cross-game plays — `dto.MyPlayPage`'s rows — shared by `SharedPlayerHistoryPage` and the admin
  * games tab. Every row links to the game itself, which is always public (`GameEndpoints.get`).
  *
  * `MyPlayHistoryPage` used to render through this too, with per-row "view" and "play again" actions; it now renders
  * its own sortable, paged table (built to `GameResultsPage`'s shape) and owns those actions itself. What is left here
  * is the plain table the two observer-less callers need.
  */
object PlayHistoryTable {

  def render(rows: Signal[List[MyPlaySummary]]): HtmlElement = {
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
          )
        ),
        tbody(children <-- rows.map(_.map(renderRow))),
      ),
    )
  }

  def maybeEmpty(rows: Signal[List[MyPlaySummary]]): Signal[Option[HtmlElement]] = {
    rows.map(list => Option.when(list.isEmpty)(div(cls := "text-base-content/70", I18n.t(UiKeys.myPlaysEmpty))))
  }

  private def renderRow(play: MyPlaySummary): HtmlElement = {
    tr(
      cls := "hover",
      td(a(cls := "link link-hover", AppRouter.router.navigateTo(Page.GameInstance(play.gameSlug)), play.gameName)),
      td(s"${play.score} / ${play.maxScore}"),
      td(play.wordCount.toString),
      td(Labels.variant(play.variant)),
      td(Formats.dateTime(play.startedAt)),
    )
  }
}
