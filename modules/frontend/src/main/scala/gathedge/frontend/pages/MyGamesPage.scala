package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GameApiClient}
import gathedge.frontend.components.{Alert, AppShell, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.MyGameSummary
import gathedge.shared.i18n.UiKeys

/** The signed-in owner's own games: name, tags, language pair, and how many times each was played.
  *
  * A personal list, not a paged admin table — `GameService.myGames` answers the whole thing in one response (there is
  * no reader with enough games for offset paging to matter), so this renders it straight through with no URL-carried
  * query state, unlike `AdminUsersPage`/`AdminAuditPage`.
  */
object MyGamesPage {

  def render(): HtmlElement = {
    AppShell.render(Page.MyGames, new MyGamesPage().render())
  }
}

private class MyGamesPage {

  private val gamesVar: Var[Option[List[MyGameSummary]]] = Var(None)
  private val errorVar: Var[Option[String]]              = Var(None)

  private val reloadBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      cls := "p-4",
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.myGamesTitle)),
      Alert.maybeError(errorVar.signal),
      child <-- gamesVar.signal.map(renderBody),
      reloadBus.events.flatMapSwitch(_ => GameApiClient.myGames()) -->
        Observer[Either[ApiError, List[MyGameSummary]]] {
          case Right(games) =>
            Var.set(gamesVar -> Some(games), errorVar -> None)
          case Left(err)    =>
            errorVar.set(Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def renderBody(games: Option[List[MyGameSummary]]): HtmlElement = {
    games match {
      case None                       =>
        div(cls := "flex justify-center p-8", span(cls := "loading loading-spinner"))
      case Some(rows) if rows.isEmpty =>
        div(cls := "text-base-content/70", I18n.t(UiKeys.myGamesEmpty))
      case Some(rows)                 =>
        renderTable(rows)
    }
  }

  private def renderTable(rows: List[MyGameSummary]): HtmlElement = {
    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table table-sm",
        thead(
          tr(
            th(I18n.t(UiKeys.myGamesNameCol)),
            th(I18n.t(UiKeys.myGamesTagsCol)),
            th(I18n.t(UiKeys.myGamesSourceCol)),
            th(I18n.t(UiKeys.myGamesTargetCol)),
            th(I18n.t(UiKeys.myGamesPlaysCol)),
          )
        ),
        tbody(rows.map(renderRow)),
      ),
    )
  }

  private def renderRow(game: MyGameSummary): HtmlElement = {
    tr(
      cls := "hover",
      td(a(cls := "link link-hover", AppRouter.router.navigateTo(Page.GameInstance(game.slug)), game.name)),
      td(renderTags(game.tagNames)),
      td(Labels.language(game.sourceLanguage)),
      td(Labels.language(game.targetLanguage)),
      td(game.playCount.toString),
    )
  }

  private def renderTags(tagNames: List[String]): HtmlElement = {
    div(
      cls := "flex flex-wrap gap-1",
      tagNames.map(name => span(cls := "badge badge-primary badge-soft badge-sm", name)),
    )
  }
}
