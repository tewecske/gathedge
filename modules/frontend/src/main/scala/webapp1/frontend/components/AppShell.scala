package webapp1.frontend.components

import com.raquo.laminar.api.L._
import com.raquo.laminar.codecs.Codec
import org.scalajs.dom
import scala.scalajs.js
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.state.AppState
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.Theme
import webapp1.shared.dto.AuthResponse

/** Themed authenticated shell: navbar (nav links + theme toggle + account menu) wrapping page-specific content. Every
  * authenticated page renders through this so nav/theme/logout stay consistent.
  *
  * Takes no user parameter on purpose: everything user-dependent (admin link, theme label, avatar initial) is read
  * reactively from [[AppState.currentUserSignal]], so a theme toggle updates the navbar in place instead of forcing
  * `App` to rebuild the whole page.
  */
object AppShell {
  def render(active: Page, content: HtmlElement): HtmlElement = new AppShell(active, content).render()

  // Laminar 17 has no keys for the popover API, which is what the account dropdown is built on.
  private val popoverAttr       = htmlAttr("popover", Codec.stringAsIs)
  private val popoverTargetAttr = htmlAttr("popovertarget", Codec.stringAsIs)

  // `child <--` can briefly hold the outgoing and incoming shell in the DOM at once, and a
  // duplicate `id` would make `popovertarget` resolve to the wrong element.
  private var instanceCounter = 0

  private def nextInstanceId(): Int = {
    instanceCounter += 1
    instanceCounter
  }
}

private class AppShell(active: Page, content: HtmlElement) {
  private val themeToggleBus = new EventBus[Unit]()
  private val logoutBus      = new EventBus[Unit]()

  private val currentUserSignal = AppState.currentUserSignal
  private val isAdminSignal     = currentUserSignal.map(_.exists(_.isAdmin)).distinct
  private val themeSignal       = currentUserSignal.map(_.map(_.theme).getOrElse(Theme.Light)).distinct
  private val emailSignal       = currentUserSignal.map(_.map(_.email).getOrElse("")).distinct
  private val initialSignal     = emailSignal.map(email => email.headOption.map(_.toUpper.toString).getOrElse("?")).distinct

  private val instanceId = AppShell.nextInstanceId()
  private val menuId     = s"user-menu-$instanceId"
  private val menuAnchor = s"--user-menu-$instanceId"

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen bg-base-200",
      renderNavbar(),
      div(cls := "p-8", content),
      // Effects live in the Observer, never in the stream's `map` — the request is the
      // only thing the stream describes.
      themeToggleBus.events.sample(themeSignal).flatMapSwitch(current => ApiClient.updateTheme(nextTheme(current))) -->
        Observer[Either[ApiError, AuthResponse]] {
          case Right(res) =>
            AppState.setUser(res.user)
          case Left(_)    =>
            () // theme toggle failure is low-stakes; silently keep prior theme
        },
      logoutBus.events.flatMapSwitch(_ => ApiClient.logout) -->
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
      case Theme.Dark  =>
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
            themeSignal.map {
              case Theme.Light =>
                "Switch to dark"
              case Theme.Dark  =>
                "Switch to light"
            }.distinct,
          onClick.mapToUnit --> themeToggleBus.writer,
        ),
        renderAccountMenuTrigger(),
        renderAccountMenu(),
      ),
    )
  }

  /** Placeholder avatar that opens [[renderAccountMenu]]. The email rides on `title` rather than in the menu body: as
    * menu text it would be a second (hidden) match for the e2e suite's `getByText(email)`.
    */
  private def renderAccountMenuTrigger(): HtmlElement = {
    button(
      cls                        := "btn btn-ghost btn-circle avatar avatar-placeholder",
      typ                        := "button",
      aria.label                 := "Account menu",
      title <-- emailSignal,
      AppShell.popoverTargetAttr := menuId,
      styleAttr                  := s"anchor-name:$menuAnchor",
      div(cls := "bg-neutral text-neutral-content w-8 rounded-full", span(cls := "text-xs", text <-- initialSignal)),
    )
  }

  /** daisyUI's popover-API dropdown: `dropdown` sits on the popover element itself and there is no `dropdown-content`.
    * Placement comes from CSS anchor positioning, hence the `anchor-name`/`position-anchor` pair.
    */
  private def renderAccountMenu(): HtmlElement = {
    ul(
      cls                  := "dropdown dropdown-end menu w-52 rounded-box bg-base-100 shadow-sm",
      AppShell.popoverAttr := "auto",
      idAttr               := menuId,
      styleAttr            := s"position-anchor:$menuAnchor",
      li(
        a(
          cls := (
            if (active == Page.Settings)
              "menu-active"
            else
              ""
          ),
          AppRouter.router.navigateTo(Page.Settings),
          "Account settings",
          onClick.mapToUnit --> Observer[Unit](_ => closeMenu()),
        )
      ),
      // No `closeMenu` needed: logout pushes Page.SignIn, which rebuilds the shell away.
      li(button(typ := "button", "Log out", onClick.mapToUnit --> logoutBus.writer)),
    )
  }

  /** A popover is light-dismissed by clicks *outside* it, never by one on its own item, and choosing "Account settings"
    * while already on `/settings` does not rebuild the shell. scalajs-dom has no binding for `hidePopover`, and jsdom
    * (the frontend specs' DOM) may not implement it at all — hence the cast and the feature check.
    */
  private def closeMenu(): Unit = {
    Option(dom.document.getElementById(menuId)).foreach { element =>
      val dyn = element.asInstanceOf[js.Dynamic]
      if (!js.isUndefined(dyn.hidePopover)) {
        dyn.hidePopover()
      }
    }
  }
}
