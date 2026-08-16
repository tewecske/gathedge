package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.Branding
import gathedge.shared.domain.Theme
import gathedge.shared.i18n.UiKeys
import gathedge.shared.dto.{AuthResponse, ClaimCodeResponse}
import org.scalajs.dom

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

  /** The signed-out shell: wordmark, language picker and theme control only, with the content centred rather than laid
    * out from the top — these pages are a single card, not a screenful.
    */
  def renderPublic(content: HtmlElement): HtmlElement = new AppShell(None, content).render()
}

private class AppShell(active: Option[Page], content: HtmlElement) {
  private val themeToggleBus = new EventBus[Unit]()
  private val logoutBus      = new EventBus[Unit]()

  /** The guest's transfer code, fetched on demand from the account menu. `guestCode` is idempotent now — the same code
    * comes back every time — so there is nothing to lose by asking for it again on every open rather than caching it
    * here across a page load.
    */
  private val codeBus = new EventBus[Unit]()
  private val codeVar = Var(Option.empty[String])

  /** Open state of the guest "sign in to a different account" confirm dialog. A plain `Var` rather than the native
    * `<dialog>`/popover forms of a daisyUI modal — those close over imperative `showModal()`/`.close()` calls or a
    * `popovertarget` id, neither of which fits a reactively-rendered element as well as toggling a class off a signal,
    * and `HTMLDialogElement.showModal` is unimplemented in jsdom, which the frontend specs run under.
    */
  private val confirmSignInOpenVar = Var(false)

  private val currentUserSignal = AppState.currentUserSignal
  private val isAdminSignal     = currentUserSignal.map(_.exists(_.isAdmin)).distinct
  private val themeSignal       = AppState.themeSignal

  /** What the account menu is labelled with. A guest has no address, so it says so instead — the menu still has to name
    * *something*, and "guest" is the true answer rather than an empty tooltip.
    */
  private val emailSignal = {
    currentUserSignal
      .map(_.map(user => user.email.getOrElse(I18n.t(UiKeys.guestAccountLabel))).getOrElse(""))
      .distinct
  }

  private val initialSignal = {
    emailSignal.map(email => email.headOption.map(_.toUpper.toString).getOrElse("?")).distinct
  }

  private val (menuId, menuAnchor)       = Popover.nextIds("user-menu")
  private val (navMenuId, navMenuAnchor) = Popover.nextIds("nav-menu")

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
      child.maybe <-- codeVar.signal.map(_.map(renderCodePanel)),
      renderSignInConfirmModal(),
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
      codeBus.events.flatMapSwitch(_ => ApiClient.guestCode) -->
        Observer[Either[ApiError, ClaimCodeResponse]] {
          case Right(response) =>
            codeVar.set(Some(response.code))
          case Left(_)         =>
            () // low-stakes: the account menu item is still there to try again
        },
    )
  }

  /** The signed-out pages are one card, so they get centred in what is left of the viewport rather than stacked under
    * the navbar the way a page of content is.
    */
  private def renderContent(): HtmlElement = {
    if (isAuthenticated) {
      div(cls := "p-4 lg:p-8", content)
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

  /** The same entry as [[navLink]], worded for a `menu` rather than for a row of buttons: inside the small-screen
    * dropdown a nav entry is a list item, and `menu-active` is what marks it there.
    *
    * The explicit `hide`: a popover is light-dismissed by clicks *outside* it, so navigating to the page already open
    * (which rebuilds nothing) would otherwise leave the menu standing.
    */
  private def navMenuItem(page: Page, label: String): HtmlElement = {
    li(
      a(
        cls := (
          if (active.contains(page))
            "menu-active"
          else
            ""
        ),
        AppRouter.router.navigateTo(page),
        label,
        onClick.mapToUnit --> Observer[Unit](_ => Popover.hide(navMenuId)),
      )
    )
  }

  /** The vocabulary is reachable by anybody, so its link is on the bar whatever the session says; Home is too, and is
    * `Public` in [[AppRouter.Page.guardFor]] for the same reason — a visitor who lands on the sign-in page still needs
    * a way back to it, without being bounced right back here. The admin link is left to the signal below, which is
    * already `false` for a visitor with no account.
    */
  private def navLinks(): List[HtmlElement] = {
    List(
      navLink(Page.Words(), I18n.t(UiKeys.navWords)),
      navLink(Page.Games, I18n.t(UiKeys.navGames)),
      navLink(Page.Home, I18n.t(UiKeys.navHome)),
    )
  }

  /** The same entries as [[navLinks]], as menu items. Built separately rather than reused: a Laminar element belongs to
    * one place in the DOM, and these two renderings of the nav are both in the document at once — only ever one of them
    * displayed, which is the whole of the responsive behaviour.
    */
  private def navMenuItems(): List[HtmlElement] = {
    List(
      navMenuItem(Page.Words(), I18n.t(UiKeys.navWords)),
      navMenuItem(Page.Games, I18n.t(UiKeys.navGames)),
      navMenuItem(Page.Home, I18n.t(UiKeys.navHome)),
    )
  }

  /** The avatar and its menu are the part of the bar that means nothing without an account — except there is always one
    * to open, whoever is looking. A signed-out visitor gets Sign in/Sign up in place of Get code/Upgrade or
    * Settings/Log out: the widget is one shape everywhere, and only its contents move with the session.
    */
  private def accountControls(): HtmlElement = {
    div(cls := "contents", renderAccountMenuTrigger(), renderAccountMenu())
  }

  /** The nav is rendered twice and shown once: a dropdown under `lg`, a centred row of buttons at `lg` and up. Both are
    * in the DOM, and the breakpoint utilities (`lg:hidden` / `hidden lg:flex`) decide which one exists as far as a
    * reader — or the accessibility tree, hence Playwright's `getByRole` — is concerned, since `hidden` is
    * `display:none`.
    *
    * The other three controls (language, theme, account) are already single icons wide, so they stay in `navbar-end` at
    * every width rather than folding into the dropdown.
    */
  private def renderNavbar(): HtmlElement = {
    div(
      cls := "navbar bg-base-100 shadow-sm gap-2",
      div(
        cls := "navbar-start gap-2",
        navDropdown(),
        span(cls := "text-lg font-semibold px-2", Branding.appName),
      ),
      div(
        cls := "navbar-center hidden lg:flex gap-2",
        navLinks(),
        child.maybe <-- isAdminSignal.map(Option.when(_)(navLink(Page.Admin(), I18n.t(UiKeys.navAdmin)))),
      ),
      div(
        cls := "navbar-end gap-2",
        // All three stay on the signed-out shell — a visitor who cannot read this page has to be able
        // to reach one they can, and the theme is the browser's choice whether or not anyone is signed in.
        LanguagePicker.render(),
        renderThemeSwap(),
        accountControls(),
      ),
    )
  }

  /** The small-screen nav: the hamburger and the popover it names, as a pair so neither can be rendered without the
    * other.
    *
    * Rendered on the signed-out shell too, now that it has an entry — the vocabulary. A visitor who lands on the
    * sign-in page has to have a way to the one screen that needs no account, and at a phone's width this dropdown is
    * the only nav there is.
    */
  private def navDropdown(): List[HtmlElement] = {
    List(renderNavMenuTrigger(), renderNavMenu())
  }

  private def renderNavMenuTrigger(): HtmlElement = {
    button(
      cls                := "btn btn-ghost btn-circle lg:hidden",
      typ                := "button",
      aria.label         := I18n.t(UiKeys.navMenu),
      Popover.targetAttr := navMenuId,
      styleAttr          := s"anchor-name:$navMenuAnchor",
      hamburgerIcon(),
    )
  }

  /** The same popover-API dropdown as the account menu, opening from the left. `lg:hidden` sits on the popover too: an
    * author `display:none` outranks the `:popover-open` display the browser applies, so a menu left open across a
    * resize closes itself rather than floating over the centred nav.
    */
  private def renderNavMenu(): HtmlElement = {
    ul(
      cls                 := "dropdown dropdown-start menu w-52 rounded-box bg-base-100 shadow-sm lg:hidden",
      Popover.popoverAttr := "auto",
      idAttr              := navMenuId,
      styleAttr           := s"position-anchor:$navMenuAnchor",
      navMenuItems(),
      child.maybe <-- isAdminSignal.map(Option.when(_)(navMenuItem(Page.Admin(), I18n.t(UiKeys.navAdmin)))),
    )
  }

  private def hamburgerIcon(): SvgElement = {
    svg.svg(
      svg.cls           := "size-5",
      svg.viewBox       := "0 0 24 24",
      svg.fill          := "none",
      svg.stroke        := "currentColor",
      svg.strokeWidth   := "2",
      svg.strokeLineCap := "round",
      svg.path(svg.d := "M4 6h16M4 12h16M4 18h16"),
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
      // Three shapes, not two: a visitor with no session at all gets Sign in/Sign up; a guest — no address, no
      // password, no linked providers, so Settings has nothing to show it — gets the two ways out of being one
      // (transfer code, real account) plus Sign in (to a *different*, already-existing account) and Log out, since a
      // guest does hold a real session even though it never performed an explicit sign-in; a full account keeps
      // Settings/Log out.
      children <-- currentUserSignal.map {
        case None                       =>
          List(
            li(
              a(
                AppRouter.router.navigateTo(Page.SignIn),
                I18n.t(UiKeys.commonSignIn),
                onClick.mapToUnit --> Observer[Unit](_ => Popover.hide(menuId)),
              )
            ),
            li(
              a(
                AppRouter.router.navigateTo(Page.SignUp),
                I18n.t(UiKeys.commonSignUp),
                onClick.mapToUnit --> Observer[Unit](_ => Popover.hide(menuId)),
              )
            ),
          )
        case Some(user) if user.isGuest =>
          List(
            li(
              button(
                typ := "button",
                I18n.t(UiKeys.guestGetCode),
                onClick.mapToUnit --> codeBus.writer,
                onClick.mapToUnit --> Observer[Unit](_ => Popover.hide(menuId)),
              )
            ),
            li(
              a(
                AppRouter.router.navigateTo(Page.SignUp),
                I18n.t(UiKeys.guestUpgrade),
                onClick.mapToUnit --> Observer[Unit](_ => Popover.hide(menuId)),
              )
            ),
            li(
              // Not `navigateTo`: signing into a different, already-existing account abandons this guest's tagged
              // words (a sign-in swaps the session, it does not merge one account into another), so the navigation
              // itself is gated on a confirmation (`renderSignInConfirmModal`) rather than being unconditional like
              // every other item here.
              button(
                typ := "button",
                I18n.t(UiKeys.commonSignIn),
                onClick.mapToUnit --> Observer[Unit] { _ =>
                  Popover.hide(menuId)
                  confirmSignInOpenVar.set(true)
                },
              )
            ),
            // No `hide` needed: logout pushes Page.SignIn, which rebuilds the shell away.
            li(button(typ := "button", I18n.t(UiKeys.navLogOut), onClick.mapToUnit --> logoutBus.writer)),
          )
        case Some(_)                    =>
          List(
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
      },
    )
  }

  /** Confirms the guest "Sign in" item before it navigates away — see [[confirmSignInOpenVar]]. `Yes`/`No` reuse
    * [[UiKeys.commonYes]]/[[UiKeys.commonNo]] rather than minting dialog-specific labels, matching the plain
    * question-worded body text.
    */
  private def renderSignInConfirmModal(): HtmlElement = {
    div(
      cls := "modal",
      cls("modal-open") <-- confirmSignInOpenVar.signal,
      div(
        cls   := "modal-box",
        p(I18n.t(UiKeys.guestSignInWarning)),
        div(
          cls := "modal-action",
          button(
            cls := "btn",
            typ := "button",
            I18n.t(UiKeys.commonNo),
            onClick.mapToUnit --> Observer[Unit](_ => confirmSignInOpenVar.set(false)),
          ),
          button(
            cls := "btn btn-primary",
            typ := "button",
            I18n.t(UiKeys.commonYes),
            onClick.mapToUnit --> Observer[Unit] { _ =>
              confirmSignInOpenVar.set(false)
              AppRouter.router.pushState(Page.SignIn)
            },
          ),
        ),
      ),
      // Closes on an outside click, same as the daisyUI docs' `modal-backdrop` recipe.
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => confirmSignInOpenVar.set(false))),
    )
  }

  /** The guest's transfer code, fetched from the account menu. A floating alert rather than something inside the
    * popover itself: the popover is light-dismissed on an outside click, and a code the reader wants to select and copy
    * needs to survive that.
    */
  private def renderCodePanel(transferCode: String): HtmlElement = {
    div(
      cls  := "fixed top-16 right-4 z-50 alert alert-info flex flex-col items-start gap-2 w-auto max-w-sm shadow-lg",
      role := "status",
      p(cls := "text-sm", I18n.t(UiKeys.guestCodeOnce)),
      div(
        cls := "flex flex-wrap items-center gap-2",
        code(cls := "font-mono text-lg tracking-wider whitespace-nowrap", transferCode),
        button(
          cls    := "btn btn-xs",
          typ    := "button",
          I18n.t(UiKeys.guestCodeCopy),
          onClick.mapToUnit --> Observer[Unit](_ => copyToClipboard(transferCode)),
        ),
        button(
          cls    := "btn btn-xs btn-ghost",
          typ    := "button",
          I18n.t(UiKeys.guestCodeClose),
          onClick.mapToUnit --> Observer[Unit](_ => codeVar.set(None)),
        ),
      ),
    )
  }

  /** Feature-checked, like [[GuestBanner]]'s own copy of this: the clipboard API is absent in jsdom and on older
    * browsers, and a copy button that throws would take the page with it. The code is on screen either way.
    */
  private def copyToClipboard(value: String): Unit = {
    try {
      val clipboard = dom.window.navigator.asInstanceOf[scala.scalajs.js.Dynamic].clipboard
      if (!scala.scalajs.js.isUndefined(clipboard)) {
        clipboard.writeText(value)
      }
    } catch { case _: Throwable => () }
  }
}
