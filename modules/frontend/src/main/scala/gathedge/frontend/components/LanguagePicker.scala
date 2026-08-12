package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.{CurrentLocale, I18n}
import gathedge.shared.domain.Locale
import gathedge.shared.domain.Locale.{code, display}
import gathedge.shared.i18n.UiKeys

/** Switches the page's language, by navigating to the same path under the other prefix.
  *
  * A popover-API dropdown, the same shape as the account menu in [[AppShell]]: the trigger carries only the current
  * language's code (`EN`, `HU`) so the navbar stays narrow whatever gets added to the list, and the menu spells each
  * one out (`HU - Magyar`) so the code is never the only thing to recognise a language by.
  *
  * Plain anchors, and a real navigation rather than a Waypoint `pushState` — the same reasoning as `OAuthButtons`, for
  * a different reason. Every route's `basePath` was fixed when the router was built, so the document has to reload for
  * a new one to take effect; that reload is also what re-fetches the catalog and lets `I18n.t` stay synchronous. Being
  * anchors, they middle-click and open-in-new-tab like any other link.
  *
  * '''Nothing here calls the API.''' Writing the choice to `localStorage` and navigating is enough: on the next load
  * `LocaleSync` sees an explicit prefix disagreeing with the account and persists it. That is the same path taken by
  * someone who edits the address bar, so the picker cannot drift from it.
  *
  * Shown to signed-out visitors too — a Hungarian speaker who cannot read the sign-in page has no way in otherwise,
  * which is why the signed-out shell renders the navbar at all.
  */
object LanguagePicker {
  def render(): HtmlElement = new LanguagePicker().render()
}

private class LanguagePicker {
  private val (menuId, menuAnchor) = Popover.nextIds("language-menu")

  def render(): HtmlElement = {
    div(renderTrigger(), renderMenu())
  }

  /** The label is the language's own code, not a translated word: `EN` reads as English in Hungarian and vice versa,
    * and the accessible name carries the translated "Language" for anyone who cannot see the two-letter hint.
    */
  private def renderTrigger(): HtmlElement = {
    button(
      cls                := "btn btn-sm btn-ghost",
      typ                := "button",
      aria.label         := I18n.t(UiKeys.navLanguage),
      Popover.targetAttr := menuId,
      styleAttr          := s"anchor-name:$menuAnchor",
      CurrentLocale.value.code.toUpperCase,
      chevronIcon(),
    )
  }

  /** daisyUI's popover-API dropdown: `dropdown` sits on the popover element itself and there is no `dropdown-content`.
    * Placement comes from CSS anchor positioning, hence the `anchor-name`/`position-anchor` pair.
    */
  private def renderMenu(): HtmlElement = {
    ul(
      cls                 := "dropdown dropdown-end menu w-52 rounded-box bg-base-100 shadow-sm",
      Popover.popoverAttr := "auto",
      idAttr              := menuId,
      styleAttr           := s"position-anchor:$menuAnchor",
      Locale.all.map(item),
    )
  }

  /** The endonym rule (see `Locale.displayName`) applies to the whole entry: `HU - Magyar` is spelled the same way in
    * every language, so a visitor looking at a UI they cannot read can still find their own.
    *
    * No `Popover.hide` here — every entry navigates, which tears the document down anyway.
    */
  private def item(locale: Locale): HtmlElement = {
    val isCurrent = locale == CurrentLocale.value
    li(
      a(
        cls          := (
          if (isCurrent)
            "menu-active"
          else
            ""
        ),
        href         := CurrentLocale.urlUnder(locale),
        // Marks the current choice for a screen reader, which cannot see "this entry looks selected".
        aria.current := (
          if (isCurrent)
            "true"
          else
            "false"
        ),
        // Not `preventDefault`: the anchor still navigates. This only leaves the choice somewhere the
        // boot script can find it on a later visit that has no prefix in the URL.
        onClick.mapTo(locale) --> Observer[Locale](CurrentLocale.store),
        s"${locale.code.toUpperCase} - ${locale.display}",
      )
    )
  }

  /** Purely an affordance: without it the trigger is two letters that do not look like they open anything. */
  private def chevronIcon(): SvgElement = {
    svg.svg(
      svg.cls         := "size-3 opacity-60",
      svg.viewBox     := "0 0 24 24",
      svg.fill        := "none",
      svg.stroke      := "currentColor",
      svg.strokeWidth := "3",
      svg.path(svg.d := "M6 9l6 6 6-6"),
    )
  }
}
