package webapp1.frontend

import com.raquo.laminar.api.L._
import webapp1.frontend.api.ApiClient
import webapp1.frontend.pages.{
  AcceptInvitePage,
  AdminUserDetailPage,
  AdminUsersPage,
  ForbiddenPage,
  GroupDetailPage,
  GroupsPage,
  NotFoundPage,
  SignInPage,
  SignUpPage,
  TodoPage,
}
import webapp1.frontend.state.AppState
import webapp1.shared.domain.User
import webapp1.shared.dto.AuthResponse

/** Root component: loads the current session once, then renders + guards pages.
  * Any page requiring a session redirects an unauthenticated visitor to sign-in,
  * and vice versa (cross-cutting behavior from summary.md) — implemented once here
  * so every page reuses it for free. A few pages (accept-invite, forbidden, not-
  * found) render regardless of auth state; see [[Page.guardFor]].
  */
object App {

  private val sessionLoadedVar: Var[Boolean] = Var(false)

  def render(): HtmlElement = {
    div(
      onMountCallback { ctx =>
        ApiClient
          .get[AuthResponse]("/api/me")
          .map {
            case Right(res) => AppState.setUser(res.user)
            case Left(_)    => AppState.clearUser()
          }
          .foreach(_ => sessionLoadedVar.set(true))(using ctx.owner)
      },
      child <-- sessionLoadedVar.signal
        .combineWithFn(AppRouter.router.currentPageSignal, AppState.currentUserSignal)((loaded, page, user) =>
          renderFor(loaded, page, user)
        ),
    )
  }

  private def renderFor(loaded: Boolean, page: Page, user: Option[User]): HtmlElement = {
    if (!loaded) {
      loadingView()
    } else {
      Page.guardFor(page) match {
        case Page.AuthGuard.RequireAuth if user.isEmpty =>
          AppRouter.router.replaceState(Page.SignIn)
          loadingView()
        case Page.AuthGuard.RequireAnon if user.isDefined =>
          AppRouter.router.replaceState(Page.Home)
          loadingView()
        case _ => renderPage(page, user)
      }
    }
  }

  private def renderPage(page: Page, user: Option[User]): HtmlElement = {
    (page, user) match {
      case (Page.SignIn, _)                => SignInPage.render()
      case (Page.SignUp, _)                => SignUpPage.render()
      case (Page.Home, Some(u))            => TodoPage.render(u)
      case (Page.Groups, Some(u))          => GroupsPage.render(u)
      case (Page.GroupDetail(id), Some(u)) => GroupDetailPage.render(u, id)
      case (Page.AcceptInvite(token), _)   => AcceptInvitePage.render(user, token)
      case (Page.Admin, Some(u)) if u.isAdmin            => AdminUsersPage.render(u)
      case (Page.Admin, Some(_))                          => ForbiddenPage.render()
      case (Page.AdminUserDetail(id), Some(u)) if u.isAdmin => AdminUserDetailPage.render(u, id)
      case (Page.AdminUserDetail(_), Some(_))               => ForbiddenPage.render()
      case (Page.Forbidden, _)             => ForbiddenPage.render()
      case (Page.NotFound, _)              => NotFoundPage.render()
      case _                               => NotFoundPage.render()
    }
  }

  private def loadingView(): HtmlElement = {
    div(cls := "min-h-screen flex items-center justify-center", span(cls := "loading loading-spinner loading-lg"))
  }
}
