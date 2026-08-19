package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiError, GameApiClient}
import gathedge.frontend.components.{Alert, AppShell, PlayHistoryTable}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.{MyPlayPage, MyPlaySummary}
import gathedge.shared.i18n.UiKeys

/** The signed-in caller's own play history across every game — see `GameService.myPlays`. Never gated by
  * `trackResults`, unlike the owner-facing `GameResultsPage`: it is always the caller's own data.
  *
  * A personal list rendered straight through with no URL-carried query state, the same shape `MyGamesPage` follows.
  */
object MyPlayHistoryPage {

  def render(): HtmlElement = {
    AppShell.render(Page.MyPlays, new MyPlayHistoryPage().render())
  }
}

private class MyPlayHistoryPage {

  private val playsVar: Var[Option[List[MyPlaySummary]]] = Var(None)
  private val errorVar: Var[Option[String]]              = Var(None)

  private val reloadBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      cls := "p-4",
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.myPlaysTitle)),
      Alert.maybeError(errorVar.signal),
      child <-- playsVar.signal.map(renderBody),
      reloadBus.events.flatMapSwitch(_ => GameApiClient.myPlays(pageSize = Some(100))) -->
        Observer[Either[ApiError, MyPlayPage]] {
          case Right(page) =>
            Var.set(playsVar -> Some(page.items), errorVar -> None)
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def renderBody(plays: Option[List[MyPlaySummary]]): HtmlElement = {
    plays match {
      case None                       =>
        div(cls := "flex justify-center p-8", span(cls := "loading loading-spinner"))
      case Some(rows) if rows.isEmpty =>
        div(cls := "text-base-content/70", I18n.t(UiKeys.myPlaysEmpty))
      case Some(rows)                 =>
        PlayHistoryTable.render(Val(rows))
    }
  }
}
