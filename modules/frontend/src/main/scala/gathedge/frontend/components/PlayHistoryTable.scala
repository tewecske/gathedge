package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.MyPlaySummary
import gathedge.shared.i18n.UiKeys

/** A page of cross-game plays — `dto.MyPlayPage`'s rows — shared by `MyPlayHistoryPage`, `SharedPlayerHistoryPage` and
  * the admin games tab. Read-only: no per-play detail, unlike `GameResultsPage`'s owner-facing table, since none of
  * those three callers has a play-detail endpoint of its own to open one from. Every row links to the game itself,
  * which is always public (`GameEndpoints.get`).
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
      td(Formats.dateTime(play.startedAt)),
    )
  }
}
