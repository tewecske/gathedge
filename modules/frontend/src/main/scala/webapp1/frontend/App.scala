package webapp1.frontend

import com.raquo.laminar.api.L._
import webapp1.frontend.api.ApiClient
import webapp1.frontend.pages.{
  AcceptInvitePage,
  AdminUserDetailPage,
  AdminUsersPage,
  CheckInboxPage,
  ForbiddenPage,
  GroupDetailPage,
  GroupMembersPage,
  GroupsPage,
  NotFoundPage,
  SettingsPage,
  SignInPage,
  SignUpPage,
  TodoPage,
  VerifyEmailPage,
}
import webapp1.frontend.state.AppState
import webapp1.shared.dto.AuthResponse

/** Root component: loads the current session once, then renders + guards pages. Any page requiring a session redirects
  * an unauthenticated visitor to sign-in, and vice versa (cross-cutting behavior from summary.md) — implemented once
  * here so every page reuses it for free. A few pages (accept-invite, forbidden, not-found) render regardless of auth
  * state; see [[Page.guardFor]].
  */
object App {

  private val sessionLoadedVar: Var[Boolean] = Var(false)

  /** The *only* user-derived facts that change which page element is built. Deliberately not the whole `User`: a theme
    * toggle (or any other profile write) must not tear down and rebuild the mounted page, discarding its `Var`s,
    * in-flight requests and half-typed form input. Everything else user-dependent is read reactively by
    * [[webapp1.frontend.components.AppShell]] from [[AppState.currentUserSignal]].
    */
  private final case class Gate(loaded: Boolean, signedIn: Boolean, isAdmin: Boolean)

  private val gateSignal: Signal[Gate] = {
    sessionLoadedVar
      .signal
      .combineWithFn(AppState.currentUserSignal)((loaded, user) =>
        Gate(loaded, signedIn = user.isDefined, isAdmin = user.exists(_.isAdmin))
      )
      .distinct
  }

  def render(): HtmlElement = {
    val viewSignal = gateSignal.combineWith(AppRouter.router.currentPageSignal)
    div(
      onMountCallback { ctx =>
        ApiClient
          .me
          .foreach {
            case Right(res) =>
              AppState.setUser(res.user)
              sessionLoadedVar.set(true)
            case Left(_) =>
              AppState.clearUser()
              sessionLoadedVar.set(true)
          }(using ctx.owner)
      },
      // Guard redirects are a side effect, so they run in an Observer rather than inside the
      // `child <--` mapping function — writing to `currentPageSignal` from within a function
      // that reads it is re-entrant, and re-runs on every re-evaluation.
      viewSignal.map(redirectTarget) -->
        Observer[Option[Page]] {
          case Some(target) =>
            AppRouter.router.replaceState(target)
          case None =>
            ()
        },
      child <-- viewSignal.map(renderFor),
    )
  }

  /** Pure: the page a guard violation should send the visitor to, if any. */
  private def redirectTarget(gateAndPage: (Gate, Page)): Option[Page] = {
    val (gate, page) = gateAndPage
    if (!gate.loaded) {
      None
    } else {
      Page.guardFor(page) match {
        case Page.AuthGuard.RequireAuth if !gate.signedIn =>
          Some(Page.SignIn)
        case Page.AuthGuard.RequireAnon if gate.signedIn =>
          Some(Page.Home)
        case _ =>
          None
      }
    }
  }

  private def renderFor(gateAndPage: (Gate, Page)): HtmlElement = {
    val (gate, page) = gateAndPage
    if (!gate.loaded || redirectTarget(gateAndPage).isDefined) {
      // The redirect observer above is what actually navigates; show the spinner meanwhile.
      loadingView()
    } else {
      renderPage(gate, page)
    }
  }

  private def renderPage(gate: Gate, page: Page): HtmlElement = {
    page match {
      case Page.SignIn =>
        SignInPage.render()
      case Page.SignUp =>
        SignUpPage.render()
      case Page.Home =>
        TodoPage.render()
      case Page.Groups =>
        GroupsPage.render()
      case Page.Settings =>
        SettingsPage.render()
      case Page.GroupDetail(id) =>
        GroupDetailPage.render(id)
      case Page.GroupMembers(id) =>
        GroupMembersPage.render(id)
      case Page.AcceptInvite(token) =>
        AcceptInvitePage.render(gate.signedIn, token)
      case Page.VerifyEmail(token) =>
        VerifyEmailPage.render(token)
      case Page.CheckInbox =>
        CheckInboxPage.render()
      case Page.Admin if gate.isAdmin =>
        AdminUsersPage.render()
      case Page.Admin =>
        ForbiddenPage.render()
      case Page.AdminUserDetail(id) if gate.isAdmin =>
        AdminUserDetailPage.render(id)
      case Page.AdminUserDetail(_) =>
        ForbiddenPage.render()
      case Page.Forbidden =>
        ForbiddenPage.render()
      case Page.NotFound =>
        NotFoundPage.render()
    }
  }

  private def loadingView(): HtmlElement = {
    div(cls := "min-h-screen flex items-center justify-center", span(cls := "loading loading-spinner loading-lg"))
  }
}
