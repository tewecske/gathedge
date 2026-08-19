package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiError, ProgressShareApiClient}
import gathedge.frontend.components.{Alert, AppShell, PlayHistoryTable}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.{MyPlayPage, MyPlaySummary, SharedWithMe}
import gathedge.shared.i18n.UiKeys

/** One sharer's play history, for a viewer that sharer has granted access to — reuses `MyPlayHistoryPage`'s table,
  * narrowed server-side to `trackResults = true` games and gated by `ProgressShareService.requireShareAccess`. The 403
  * that check can still answer (a revoked share, or a stale local link) is what actually enforces it.
  */
object SharedPlayerHistoryPage {

  def render(sharerUserId: Long): HtmlElement = {
    AppShell.render(Page.SharedPlayerHistory(sharerUserId), new SharedPlayerHistoryPage(sharerUserId).render())
  }
}

private class SharedPlayerHistoryPage(sharerUserId: Long) {

  private val playsVar: Var[Option[List[MyPlaySummary]]] = Var(None)
  private val labelVar: Var[Option[String]]              = Var(None)
  private val errorVar: Var[Option[String]]              = Var(None)

  private val reloadBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      cls := "p-4",
      h1(
        cls := "text-2xl font-bold mb-4",
        child.text <-- labelVar.signal.map(_.getOrElse(I18n.t(UiKeys.sharedProgressHistoryTitle))),
      ),
      Alert.maybeError(errorVar.signal),
      child <-- playsVar.signal.map(renderBody),
      reloadBus.events.flatMapSwitch(_ => ProgressShareApiClient.sharedWithMe()) -->
        Observer[Either[ApiError, List[SharedWithMe]]] {
          case Right(list) =>
            labelVar.set(
              list.find(_.sharerUserId == sharerUserId).map(_.email.getOrElse(I18n.t(UiKeys.sharedProgressGuestBadge)))
            )
          case Left(_)     =>
            ()
        },
      reloadBus.events.flatMapSwitch(_ => ProgressShareApiClient.sharerPlays(sharerUserId, pageSize = Some(100))) -->
        Observer[Either[ApiError, MyPlayPage]] {
          case Right(page) =>
            Var.set(playsVar -> Some(page.items), errorVar -> None)
          case Left(err)   =>
            Var.set(playsVar -> Some(Nil), errorVar -> Some(err.message))
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
