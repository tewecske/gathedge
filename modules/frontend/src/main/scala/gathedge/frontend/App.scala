package gathedge.frontend

import com.raquo.laminar.api.L._
import com.raquo.waypoint.SplitRender
import gathedge.frontend.api.ApiClient
import gathedge.frontend.pages.{
  AdminAuditPage,
  AdminSystemPage,
  AdminUserDetailPage,
  AdminUsersPage,
  CheckInboxPage,
  ForbiddenPage,
  HomePage,
  NotFoundPage,
  SettingsPage,
  SignInPage,
  SignUpPage,
  VerifyEmailPage,
}
import gathedge.frontend.i18n.LocaleSync
import gathedge.frontend.listing.{AuditQuery, UserQuery}
import gathedge.frontend.state.AppState
import gathedge.shared.domain.Locale
import gathedge.shared.dto.AuthResponse

/** Root component: loads the current session once, then renders + guards pages. Any page requiring a session redirects
  * an unauthenticated visitor to sign-in, and vice versa (cross-cutting behavior from summary.md) — implemented once
  * here so every page reuses it for free. A few pages (verify-email, check-inbox, forbidden, not-found) render
  * regardless of auth state; see [[Page.guardFor]].
  */
object App {

  private val sessionLoadedVar: Var[Boolean] = Var(false)

  /** The *only* user-derived facts that change which page element is built. Deliberately not the whole `User`: a theme
    * toggle (or any other profile write) must not tear down and rebuild the mounted page, discarding its `Var`s,
    * in-flight requests and half-typed form input. Everything else user-dependent is read reactively by
    * [[gathedge.frontend.components.AppShell]] from [[AppState.currentUserSignal]].
    */
  private final case class Gate(loaded: Boolean, signedIn: Boolean, isAdmin: Boolean)

  private val gateSignal: Signal[Gate] = {
    sessionLoadedVar.signal
      .combineWithFn(AppState.currentUserSignal)((loaded, user) =>
        Gate(loaded, signedIn = user.isDefined, isAdmin = user.exists(_.isAdmin))
      )
      .distinct
  }

  def render(): HtmlElement = {
    // `.distinct` matters: `currentPageSignal` is not deduplicated (Waypoint builds it from a merge
    // of route events), so navigating to the page already displayed emits it again — and every
    // emission here rebuilds the page element, discarding its state and re-running its mount loads.
    val viewSignal = gateSignal.combineWith(AppRouter.router.currentPageSignal).distinct
    div(
      onMountCallback { ctx =>
        ApiClient.me
          .foreach {
            case Right(res) =>
              AppState.setUser(res.user)
              reconcileLocale(res.user.locale)(using ctx.owner)
              sessionLoadedVar.set(true)
            case Left(_)    =>
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
          case None         =>
            ()
        },
      child <-- renderers(viewSignal).signal,
    )
  }

  /** The two administrator listings hold their paging, ordering and filtering in the URL, so every keystroke in a
    * search box produces a new `Page` value. They therefore cannot be rendered by mapping the page to an element the
    * way every other screen is: that would discard the mounted page on each change, taking the input focus, the
    * in-flight request and the loaded rows with it.
    *
    * Waypoint's signal renderers are the answer — each builds its element once and feeds it the query, and drops it
    * only when another renderer takes over. Everything else keeps the old behaviour, which is why `viewSignal` is still
    * `.distinct`: the catch-all below re-runs on every emission.
    */
  private def renderers(viewSignal: Signal[(Gate, Page)]): SplitRender[(Gate, Page), HtmlElement] = {
    SplitRender[(Gate, Page), HtmlElement](viewSignal)
      .collectSignalPF[UserQuery] { case (gate, page: Page.Admin) if showsAdminScreen(gate) => page.query }(query =>
        AdminUsersPage.render(query, onAdminQuery)
      )
      .collectSignalPF[AuditQuery] { case (gate, page: Page.AdminAudit) if showsAdminScreen(gate) => page.query }(
        query => AdminAuditPage.render(query, onAdminAuditQuery)
      )
      .collectStaticPF { case gateAndPage => renderFor(gateAndPage) }
  }

  /** `loaded` as well as `isAdmin`: the session and the user land in two separate writes, so there is an instant in
    * which the user is known to be an administrator and the gate is not open yet. The catch-all shows the spinner for
    * it, and must be the one that answers.
    */
  private def showsAdminScreen(gate: Gate): Boolean = gate.loaded && gate.isAdmin

  /** Writing the listing state back to the URL is what makes it bookmarkable; whether the write is a `pushState` or a
    * `replaceState` is what decides where the back button goes.
    *
    * A page turn, a column, a page size, a filter — each is a place the reader can want to return to, so each gets a
    * history entry. The one exception is a search term being typed out further ([[UserQuery.refines]]): the box writes
    * on a pause in typing, and an entry per pause would leave the back button walking through the reader's own words. A
    * refinement therefore replaces, so one search costs one entry however long the term is.
    *
    * A write that changes nothing does not touch the history at all — [[AdminAuditPage]]'s Apply button re-asks for the
    * listing it is already showing, and that is a reload, not a navigation.
    */
  private def navigate(next: Page, replace: Boolean): Unit = {
    if (AppRouter.router.currentPageSignal.now() == next) {
      ()
    } else if (replace) {
      AppRouter.router.replaceState(next)
    } else {
      AppRouter.router.pushState(next)
    }
  }

  private val onAdminQuery: Observer[UserQuery] = {
    Observer { query =>
      val refinesSearch = {
        AppRouter.router.currentPageSignal.now() match {
          case Page.Admin(previous) =>
            query.refines(previous)
          case _                    =>
            false
        }
      }
      navigate(Page.Admin(query), replace = refinesSearch)
    }
  }

  /** No refinement case: the audit trail's two filters are applied on a button, so every change here is deliberate. */
  private val onAdminAuditQuery: Observer[AuditQuery] = {
    Observer(query => navigate(Page.AdminAudit(query), replace = false))
  }

  /** Settles the language once the account is known. [[LocaleSync]] holds the rule and does the storing and the
    * navigating; the one thing it cannot do is talk to the API, so the write-back lives here where there is an owner to
    * subscribe with.
    *
    * The write-back fires only when the visitor arrived on an explicit prefix that disagrees with the account, which is
    * the case that means "they have changed their mind about the language" — so it happens once, and the next load
    * agrees. A failure is ignored on purpose: the page is already in the right language, and nagging about a preference
    * that did not save would be worse than saving it next time.
    */
  private def reconcileLocale(accountLocale: Locale)(using owner: Owner): Unit = {
    LocaleSync.reconcile(accountLocale) match {
      case LocaleSync.Action.Persist(chosen) =>
        ApiClient.updateLocale(chosen).foreach(_ => ())
      case _                                 =>
        ()
    }
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
        case Page.AuthGuard.RequireAnon if gate.signedIn  =>
          Some(Page.Home)
        case _                                            =>
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
      case Page.SignIn                              =>
        SignInPage.render()
      case Page.SignUp                              =>
        SignUpPage.render()
      case Page.Home                                =>
        HomePage.render()
      case Page.Settings                            =>
        SettingsPage.render()
      case Page.VerifyEmail(token)                  =>
        VerifyEmailPage.render(token)
      case Page.CheckInbox                          =>
        CheckInboxPage.render()
      // The two listings reach here only when the gate said no: a signed-in administrator gets them from the signal
      // renderers above, which is the only way they keep their state across a query change.
      case Page.Admin(_) | Page.AdminAudit(_)       =>
        ForbiddenPage.render()
      case Page.AdminUserDetail(id) if gate.isAdmin =>
        AdminUserDetailPage.render(id)
      case Page.AdminUserDetail(_)                  =>
        ForbiddenPage.render()
      case Page.AdminSystem if gate.isAdmin         =>
        AdminSystemPage.render()
      case Page.AdminSystem                         =>
        ForbiddenPage.render()
      case Page.Forbidden                           =>
        ForbiddenPage.render()
      case Page.NotFound                            =>
        NotFoundPage.render()
    }
  }

  private def loadingView(): HtmlElement = {
    div(cls := "min-h-screen flex items-center justify-center", span(cls := "loading loading-spinner loading-lg"))
  }
}
