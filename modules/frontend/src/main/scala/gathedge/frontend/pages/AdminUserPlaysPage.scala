package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{AdminSubmenu, AppShell, PlayHistoryListing}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.MyPlayQuery
import gathedge.shared.domain.User
import gathedge.shared.i18n.UiKeys

/** One account's play history across every game, for an administrator. The listing itself is
  * [[gathedge.frontend.components.PlayHistoryListing]], shared with [[SharedPlayerHistoryPage]]; what stays here is the
  * chrome an administrator gets and a viewer does not — the submenu, the back link, and the heading naming the account.
  *
  * Both calls are admin-scoped: `AdminApiClient.userPlayResults` (`GameService.resultsForPlayer`) rather than the
  * player-facing `GameApiClient.getResults`, which is owner-only. Like [[GameResultsPage]] it carries its whole listing
  * state in the URL, so it takes a `Signal[MyPlayQuery]` and an `Observer[MyPlayQuery]` the same way; `App` supplies
  * both, plus the `userId` the URL's path segment carries.
  */
object AdminUserPlaysPage {

  def render(userId: Long, query: Signal[MyPlayQuery], onQuery: Observer[MyPlayQuery]): HtmlElement = {
    AppShell.render(Page.AdminUserPlays(userId), new AdminUserPlaysPage(userId, query, onQuery).render())
  }
}

private class AdminUserPlaysPage(userId: Long, pageQuery: Signal[MyPlayQuery], onQuery: Observer[MyPlayQuery]) {

  // The viewed account, for the heading — the one place this page names whose history it is.
  private val userVar: Var[Option[User]] = Var(None)
  private val userLoadBus                = new EventBus[Unit]()

  private val headingSignal = userVar.signal.map { user =>
    I18n.t(UiKeys.adminUserPlaysTitle, user.flatMap(_.email).getOrElse(s"#$userId"))
  }

  def render(): HtmlElement = {
    div(
      AdminSubmenu.render(Page.AdminUserPlays(userId)),
      div(
        cls := "mb-4",
        a(
          cls := "link",
          AppRouter.router.navigateTo(Page.AdminUserDetail(userId)),
          I18n.t(UiKeys.adminUserPlaysBack),
        ),
      ),
      div(
        cls := "mb-4",
        h1(cls := "text-2xl font-bold", child.text <-- headingSignal),
      ),
      PlayHistoryListing.render(pageQuery, onQuery, load, loadResults),
      userLoadBus.events.flatMapSwitch(_ => AdminApiClient.getUser(userId)) -->
        Observer[Either[ApiError, User]] {
          case Right(user) =>
            userVar.set(Some(user))
          case Left(_)     =>
            userVar.set(None)
        },
      onMountCallback(_ => userLoadBus.emit(())),
    )
  }

  private def load(query: MyPlayQuery) = {
    AdminApiClient.userPlays(
      userId,
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      search = Option(query.search).filter(_.nonEmpty),
    )
  }

  private def loadResults(playId: Long) = AdminApiClient.userPlayResults(userId, playId)
}
