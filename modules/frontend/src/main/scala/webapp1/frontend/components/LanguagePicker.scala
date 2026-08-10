package webapp1.frontend.components

import com.raquo.laminar.api.L._
import webapp1.frontend.i18n.{CurrentLocale, I18n}
import webapp1.shared.domain.Locale
import webapp1.shared.domain.Locale.display

/** Switches the page's language, by navigating to the same path under the other prefix.
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
  * Shown to signed-out visitors too — a Hungarian speaker who cannot read the sign-in page has no way in otherwise.
  */
object LanguagePicker {

  def render(): HtmlElement = {
    div(
      cls        := "join",
      aria.label := I18n.t("nav.language"),
      Locale.all.map(option),
    )
  }

  private def option(locale: Locale): HtmlElement = {
    val isCurrent          = locale == CurrentLocale.value
    a(
      cls          := "btn btn-sm join-item " + (
        if (isCurrent)
          "btn-neutral"
        else
          "btn-ghost"
      ),
      href         := CurrentLocale.urlUnder(locale),
      // Marks the current choice for a screen reader, which cannot see "this button looks pressed".
      aria.current := (
        if (isCurrent)
          "true"
        else
          "false"
      ),
      // Not `preventDefault`: the anchor still navigates. This only leaves the choice somewhere the
      // boot script can find it on a later visit that has no prefix in the URL.
      onClick.mapTo(locale) --> Observer[Locale](CurrentLocale.store),
      locale.display,
    )
  }
}
