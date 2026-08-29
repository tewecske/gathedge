package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiError, ProgressShareApiClient}
import gathedge.frontend.components.{AppShell, PlayHistoryListing}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.MyPlayQuery
import gathedge.shared.dto.SharedWithMe
import gathedge.shared.i18n.UiKeys

/** One sharer's play history, for a viewer that sharer has granted access to — the same listing an administrator gets
  * in [[AdminUserPlaysPage]], through the same [[gathedge.frontend.components.PlayHistoryListing]], and gated
  * server-side by `ProgressShareService.requireShareAccess`. The 403 that check can still answer (a revoked share, or a
  * stale local link) is what actually enforces it, on the listing and on the detail modal alike.
  *
  * The heading names the sharer, which only `sharedWithMe` knows — there is no endpoint letting a viewer read one
  * account, and there should not be.
  *
  * Like the administrator's copy it carries its whole listing state in the URL, so it takes a `Signal[MyPlayQuery]` and
  * an `Observer[MyPlayQuery]`; `App` supplies both, plus the `sharerUserId` the URL's path segment carries.
  */
object SharedPlayerHistoryPage {

  def render(sharerUserId: Long, query: Signal[MyPlayQuery], onQuery: Observer[MyPlayQuery]): HtmlElement = {
    AppShell.render(
      Page.SharedPlayerHistory(sharerUserId),
      new SharedPlayerHistoryPage(sharerUserId, query, onQuery).render(),
    )
  }
}

private class SharedPlayerHistoryPage(
  sharerUserId: Long,
  pageQuery: Signal[MyPlayQuery],
  onQuery: Observer[MyPlayQuery],
) {

  private val labelVar: Var[Option[String]] = Var(None)
  private val labelLoadBus                  = new EventBus[Unit]()

  private val headingSignal = labelVar.signal.map(_.getOrElse(I18n.t(UiKeys.sharedProgressHistoryTitle)))

  def render(): HtmlElement = {
    div(
      cls := "p-4",
      h1(cls := "text-2xl font-bold mb-4", child.text <-- headingSignal),
      PlayHistoryListing.render(pageQuery, onQuery, load, loadResults),
      labelLoadBus.events.flatMapSwitch(_ => ProgressShareApiClient.sharedWithMe()) -->
        Observer[Either[ApiError, List[SharedWithMe]]] {
          case Right(list) =>
            labelVar.set(
              list.find(_.sharerUserId == sharerUserId).map(_.email.getOrElse(I18n.t(UiKeys.sharedProgressGuestBadge)))
            )
          case Left(_)     =>
            ()
        },
      onMountCallback(_ => labelLoadBus.emit(())),
    )
  }

  private def load(query: MyPlayQuery) = {
    ProgressShareApiClient.sharerPlays(
      sharerUserId,
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      search = Option(query.search).filter(_.nonEmpty),
    )
  }

  private def loadResults(playId: Long) = ProgressShareApiClient.sharerPlayResults(sharerUserId, playId)
}
