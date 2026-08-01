package webapp1.frontend.components

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.state.AppState
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.Theme
import webapp1.shared.dto.{AuthResponse, UpdateThemeRequest}

/** Themed authenticated shell: navbar (nav links + theme toggle + logout) wrapping page-specific content. Every
  * authenticated page renders through this so nav/theme/logout stay consistent.
  *
  * Takes no user parameter on purpose: everything user-dependent (admin link, theme label) is read reactively from
  * [[AppState.currentUserSignal]], so a theme toggle updates the navbar in place instead of forcing `App` to rebuild
  * the whole page.
  */
object AppShell {
  def render(active: Page, content: HtmlElement): HtmlElement = new AppShell(active, content).render()
}

private class AppShell(active: Page, content: HtmlElement) {
  private val themeToggleBus = new EventBus[Unit]()
  private val logoutBus = new EventBus[Unit]()

  private val currentUserSignal = AppState.currentUserSignal
  private val isAdminSignal = currentUserSignal.map(_.exists(_.isAdmin)).distinct
  private val themeSignal = currentUserSignal.map(_.map(_.theme).getOrElse(Theme.Light)).distinct

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen bg-base-200",
      renderNavbar(),
      div(cls := "p-8", content),
      // Effects live in the Observer, never in the stream's `map` — the request is the
      // only thing the stream describes.
      themeToggleBus
        .events
        .sample(themeSignal)
        .flatMapSwitch(current =>
          ApiClient.put[UpdateThemeRequest, AuthResponse]("/api/me/theme", UpdateThemeRequest(nextTheme(current)))
        ) -->
        Observer[Either[ApiError, AuthResponse]] {
          case Right(res) =>
            AppState.setUser(res.user)
          case Left(_) =>
            () // theme toggle failure is low-stakes; silently keep prior theme
        },
      logoutBus.events.flatMapSwitch(_ => ApiClient.postNoContent("/api/auth/logout")) -->
        Observer[Either[ApiError, Unit]] { _ =>
          // Whether or not the server acknowledged, drop the client-side session.
          AppState.clearUser()
          AppRouter.router.pushState(Page.SignIn)
        },
    )
  }

  private def nextTheme(theme: Theme): Theme = {
    theme match {
      case Theme.Light =>
        Theme.Dark
      case Theme.Dark =>
        Theme.Light
    }
  }

  private def navLink(page: Page, label: String): HtmlElement = {
    val isActive = page == active
    a(
      cls := "btn btn-sm " + (
        if (isActive)
          "btn-neutral"
        else
          "btn-ghost"
      ),
      AppRouter.router.navigateTo(page),
      label,
    )
  }

  private def renderNavbar(): HtmlElement = {
    div(
      cls := "navbar bg-base-100 shadow-sm gap-2",
      div(
        cls := "navbar-start gap-2",
        span(cls := "text-lg font-semibold px-2", "webapp1"),
        navLink(Page.Home, "Todo"),
        navLink(Page.Groups, "Groups"),
        child.maybe <-- isAdminSignal.map(Option.when(_)(navLink(Page.Admin, "Admin"))),
      ),
      div(
        cls := "navbar-end gap-2",
        button(
          cls := "btn btn-ghost btn-sm",
          typ := "button",
          text <--
            themeSignal
              .map {
                case Theme.Light =>
                  "Switch to dark"
                case Theme.Dark =>
                  "Switch to light"
              }
              .distinct,
          onClick.mapToUnit --> themeToggleBus.writer,
        ),
        button(cls := "btn btn-ghost btn-sm", typ := "button", "Log out", onClick.mapToUnit --> logoutBus.writer),
      ),
    )
  }
}
