package webapp1.frontend.components

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.i18n.I18n
import webapp1.frontend.state.AppState
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.Theme
import webapp1.shared.i18n.UiKeys
import webapp1.shared.dto.AuthResponse

/** Themed shell: navbar wrapping page-specific content. Every page renders through this so the wordmark, the language
  * picker and the theme control sit in the same place whoever is looking.
  *
  * Two shapes, one navbar. [[render]] is the authenticated one — nav links, account menu, logout. [[renderPublic]] is
  * what the signed-out pages (sign-in, sign-up) use: the same bar with everything requiring a session left out, so
  * those pages need no language picker or theme toggle of their own.
  *
  * Takes no user parameter on purpose: everything user-dependent (admin link, theme label, avatar initial) is read
  * reactively from [[AppState]], so a theme toggle updates the navbar in place instead of forcing `App` to rebuild the
  * whole page.
  */
object AppShell {

  /** The signed-in shell. `active` is the page to mark in the nav. */
  def render(active: Page, content: HtmlElement): HtmlElement = new AppShell(Some(active), content).render()

  /** The signed-out shell: wordmark, language picker and theme control only, with the content centred rather than
    * laid out from the top — these pages are a single card, not a screenful.
    */
  def renderPublic(content: HtmlElement): HtmlElement = new AppShell(None, content).render()
}

private class AppShell(active: Option[Page], content: HtmlElement) {
  private val themeToggleBus = new EventBus[Unit]()
  private val logoutBus      = new EventBus[Unit]()

  private val currentUserSignal = AppState.currentUserSignal
  private val isAdminSignal     = currentUserSignal.map(_.exists(_.isAdmin)).distinct
  private val themeSignal       = AppState.themeSignal
  private val emailSignal       = currentUserSignal.map(_.map(_.email).getOrElse("")).distinct
  private val initialSignal     = emailSignal.map(email => email.headOption.map(_.toUpper.toString).getOrElse("?")).distinct

  private val (menuId, menuAnchor) = Popover.nextIds("user-menu")

  private val isAuthenticated = active.isDefined

  /** The theme the click asks for, paired with whether there is an account to persist it to. Sampled rather than read
    * off a `Var`, so the two observers below cannot disagree about which theme a single click meant.
    */
  private val themeRequestStream = {
    themeToggleBus.events
      .sample(themeSignal, AppState.isSignedInSignal)
      .map { case (current, signedIn) =>
        (nextTheme(current), signedIn)
      }
  }

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen flex flex-col bg-base-200",
      renderNavbar(),
      renderContent(),
      // Effects live in the Observer, never in the stream's `map` — the request is the
      // only thing the stream describes.
      themeRequestStream
        .collect { case (theme, true) =>
          theme
        }
        .flatMapSwitch(theme => ApiClient.updateTheme(theme)) -->
        Observer[Either[ApiError, AuthResponse]] {
          case Right(res) =>
            AppState.setUser(res.user)
          case Left(_)    =>
            () // theme toggle failure is low-stakes; silently keep prior theme
        },
      // Nobody to persist it to: the choice is the browser's, and `AppState` is what remembers it
      // across loads. This is the whole of the theme control on the signed-out pages.
      themeRequestStream.collect { case (theme, false) =>
        theme
      } --> Observer[Theme](AppState.setTheme),
      logoutBus.events.flatMapSwitch(_ => ApiClient.logout) -->
        Observer[Either[ApiError, Unit]] { _ =>
          // Whether or not the server acknowledged, drop the client-side session.
          AppState.clearUser()
          AppRouter.router.pushState(Page.SignIn)
        },
    )
  }

  /** The signed-out pages are one card, so they get centred in what is left of the viewport rather than stacked under
    * the navbar the way a page of content is.
    */
  private def renderContent(): HtmlElement = {
    if (isAuthenticated) {
      div(cls := "p-8", content)
    } else {
      div(cls := "flex-1 flex items-center justify-center p-4", content)
    }
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
    val isActive = active.contains(page)
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

  /** Empty on the signed-out shell: every one of these needs a session. The admin link is left to the signal below,
    * which is already `false` for a visitor with no account.
    */
  private def navLinks(): List[HtmlElement] = {
    if (isAuthenticated) {
      List(navLink(Page.Home, I18n.t(UiKeys.navTodo)), navLink(Page.Groups, I18n.t(UiKeys.navGroups)))
    } else {
      Nil
    }
  }

  /** The avatar and its menu are the part of the bar that means nothing without an account. Built as a pair so the
    * trigger and the popover it names can never be rendered one without the other.
    */
  private def accountControls(): List[HtmlElement] = {
    if (isAuthenticated) {
      List(renderAccountMenuTrigger(), renderAccountMenu())
    } else {
      Nil
    }
  }

  private def renderNavbar(): HtmlElement = {
    div(
      cls := "navbar bg-base-100 shadow-sm gap-2",
      div(
        cls := "navbar-start gap-2",
        span(cls := "text-lg font-semibold px-2", "webapp1"),
        navLinks(),
        child.maybe <-- isAdminSignal.map(Option.when(_)(navLink(Page.Admin, I18n.t(UiKeys.navAdmin)))),
      ),
      div(
        cls := "navbar-end gap-2",
        // Both stay on the signed-out shell — a visitor who cannot read this page has to be able to
        // reach one they can, and the theme is the browser's choice whether or not anyone is signed in.
        LanguagePicker.render(),
        renderThemeSwap(),
        accountControls(),
      ),
    )
  }

  /** daisyUI theme controller in a `swap`: a checked `value="dark"` checkbox themes the page straight from CSS
    * (`:root:has(input.theme-controller[value=dark]:checked)`), so the icon and the colours flip together.
    * [[AppState.applyTheme]]'s `data-theme` stays the persisted mirror of the same fact — the two agree because both
    * come from [[AppState.themeSignal]], and where they momentarily wouldn't, the `:has` selector is the more specific
    * one.
    *
    * `controlled` rather than a bare `checked <--`: for a signed-in user the *request* is what decides the theme, so a
    * failed `updateTheme` has to leave the checkbox (and hence the CSS above) on the old one. Uncontrolled, the click
    * would flip the page and nothing would ever flip it back, since a failure makes `themeSignal` emit nothing. Signed
    * out there is no request and the write is local and immediate.
    *
    * The label carries no text — the two translated strings become the checkbox's accessible name.
    */
  private def renderThemeSwap(): HtmlElement = {
    label(
      cls := "swap swap-rotate btn btn-ghost btn-circle",
      input(
        typ   := "checkbox",
        cls   := "theme-controller",
        value := "dark",
        aria.label <--
          themeSignal.map {
            case Theme.Light =>
              I18n.t(UiKeys.navThemeDark)
            case Theme.Dark  =>
              I18n.t(UiKeys.navThemeLight)
          }.distinct,
        controlled(
          checked <-- themeSignal.map(_ == Theme.Dark),
          onClick.mapToUnit --> themeToggleBus.writer,
        ),
      ),
      sunIcon(),
      moonIcon(),
    )
  }

  /** Shown while the light theme is active (the swap's "off" face). `fill="currentColor"` is what makes the icon follow
    * the navbar's text colour in either theme.
    */
  private def sunIcon(): SvgElement = {
    svg.svg(
      svg.cls     := "swap-off size-5",
      svg.viewBox := "0 0 24 24",
      svg.fill    := "currentColor",
      svg.path(
        svg.d := "M5.64,17l-.71.71a1,1,0,0,0,0,1.41,1,1,0,0,0,1.41,0l.71-.71A1,1,0,0,0,5.64,17ZM5,12a1,1,0,0,0-1-1H3a1,1,0,0,0,0,2H4A1,1,0,0,0,5,12Zm7-7a1,1,0,0,0,1-1V3a1,1,0,0,0-2,0V4A1,1,0,0,0,12,5ZM5.64,7.05a1,1,0,0,0,.7.29,1,1,0,0,0,.71-.29,1,1,0,0,0,0-1.41l-.71-.71A1,1,0,0,0,4.93,6.34Zm12,.29a1,1,0,0,0,.7-.29l.71-.71a1,1,0,1,0-1.41-1.41L17,5.64a1,1,0,0,0,0,1.41A1,1,0,0,0,17.66,7.34ZM21,11H20a1,1,0,0,0,0,2h1a1,1,0,0,0,0-2Zm-9,8a1,1,0,0,0-1,1v1a1,1,0,0,0,2,0V20A1,1,0,0,0,12,19ZM18.36,17A1,1,0,0,0,17,18.36l.71.71a1,1,0,0,0,1.41,0,1,1,0,0,0,0-1.41ZM12,6.5A5.5,5.5,0,1,0,17.5,12,5.51,5.51,0,0,0,12,6.5Zm0,9A3.5,3.5,0,1,1,15.5,12,3.5,3.5,0,0,1,12,15.5Z"
      ),
    )
  }

  /** Shown while the dark theme is active (the swap's "on" face). */
  private def moonIcon(): SvgElement = {
    svg.svg(
      svg.cls     := "swap-on size-5",
      svg.viewBox := "0 0 24 24",
      svg.fill    := "currentColor",
      svg.path(
        svg.d := "M21.64,13a1,1,0,0,0-1.05-.14,8.05,8.05,0,0,1-3.37.73A8.15,8.15,0,0,1,9.08,5.49a8.59,8.59,0,0,1,.25-2A1,1,0,0,0,8,2.36,10.14,10.14,0,1,0,22,14.05,1,1,0,0,0,21.64,13Zm-9.5,6.69A8.14,8.14,0,0,1,7.08,5.22v.27A10.15,10.15,0,0,0,17.22,15.63a9.79,9.79,0,0,0,2.1-.22A8.11,8.11,0,0,1,12.14,19.73Z"
      ),
    )
  }

  /** Placeholder avatar that opens [[renderAccountMenu]]. The email rides on `title` rather than in the menu body: as
    * menu text it would be a second (hidden) match for the e2e suite's `getByText(email)`.
    */
  private def renderAccountMenuTrigger(): HtmlElement = {
    button(
      cls                := "btn btn-ghost btn-circle avatar avatar-placeholder",
      typ                := "button",
      aria.label         := I18n.t(UiKeys.navAccountMenu),
      title <-- emailSignal,
      Popover.targetAttr := menuId,
      styleAttr          := s"anchor-name:$menuAnchor",
      div(cls := "bg-neutral text-neutral-content w-8 rounded-full", span(cls := "text-xs", text <-- initialSignal)),
    )
  }

  /** daisyUI's popover-API dropdown: `dropdown` sits on the popover element itself and there is no `dropdown-content`.
    * Placement comes from CSS anchor positioning, hence the `anchor-name`/`position-anchor` pair.
    */
  private def renderAccountMenu(): HtmlElement = {
    ul(
      cls                 := "dropdown dropdown-end menu w-52 rounded-box bg-base-100 shadow-sm",
      Popover.popoverAttr := "auto",
      idAttr              := menuId,
      styleAttr           := s"position-anchor:$menuAnchor",
      li(
        a(
          cls := (
            if (active.contains(Page.Settings))
              "menu-active"
            else
              ""
          ),
          AppRouter.router.navigateTo(Page.Settings),
          I18n.t(UiKeys.settingsTitle),
          // Choosing "Account settings" while already on /settings does not rebuild the shell, and a
          // popover is never light-dismissed by a click on its own item.
          onClick.mapToUnit --> Observer[Unit](_ => Popover.hide(menuId)),
        )
      ),
      // No `hide` needed: logout pushes Page.SignIn, which rebuilds the shell away.
      li(button(typ := "button", I18n.t(UiKeys.navLogOut), onClick.mapToUnit --> logoutBus.writer)),
    )
  }
}
