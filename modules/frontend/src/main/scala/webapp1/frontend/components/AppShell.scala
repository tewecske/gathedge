package webapp1.frontend.components

import com.raquo.laminar.api.L._
import webapp1.frontend.api.ApiClient
import webapp1.frontend.state.AppState
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.{Theme, User}
import webapp1.shared.dto.{AuthResponse, UpdateThemeRequest}

/** Themed authenticated shell: navbar (nav links + theme toggle + logout) wrapping page-specific content. Every
  * authenticated page renders through this so nav/ theme/logout stay consistent.
  */
object AppShell {
  def render(user: User, active: Page, content: HtmlElement): HtmlElement = new AppShell(user, active, content).render()
}

private class AppShell(initialUser: User, active: Page, content: HtmlElement) {
  private val themeToggleBus = new EventBus[Unit]()
  private val logoutBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen bg-base-200",
      renderNavbar(),
      div(cls := "p-8", content),
      themeToggleBus.events.flatMapSwitch(_ => toggleTheme()) --> Observer[Unit](_ => ()),
      logoutBus.events.flatMapSwitch(_ => logout()) --> Observer[Unit](_ => ()),
    )
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
        child.maybe <--
          AppState.currentUserSignal.map(_.exists(_.isAdmin)).map(Option.when(_)(navLink(Page.Admin, "Admin"))),
      ),
      div(
        cls := "navbar-end gap-2",
        button(
          cls := "btn btn-ghost btn-sm",
          typ := "button",
          text <--
            AppState
              .currentUserSignal
              .map(_.map(_.theme).getOrElse(initialUser.theme))
              .map {
                case Theme.Light =>
                  "Switch to dark"
                case Theme.Dark =>
                  "Switch to light"
              },
          onClick.mapToUnit --> themeToggleBus.writer,
        ),
        button(cls := "btn btn-ghost btn-sm", typ := "button", "Log out", onClick.mapToUnit --> logoutBus.writer),
      ),
    )
  }

  private def toggleTheme() = {
    val current = AppState.currentUserVar.now().map(_.theme).getOrElse(initialUser.theme)
    val next = {
      current match {
        case Theme.Light =>
          Theme.Dark
        case Theme.Dark =>
          Theme.Light
      }
    }
    ApiClient
      .put[UpdateThemeRequest, AuthResponse]("/api/me/theme", UpdateThemeRequest(next))
      .map {
        case Right(res) =>
          AppState.setUser(res.user)
        case Left(_) =>
          () // theme toggle failure is low-stakes; silently keep prior theme
      }
  }

  private def logout() = {
    ApiClient
      .postNoContent("/api/auth/logout")
      .map { _ =>
        AppState.clearUser()
        AppRouter.router.pushState(Page.SignIn)
      }
  }
}
