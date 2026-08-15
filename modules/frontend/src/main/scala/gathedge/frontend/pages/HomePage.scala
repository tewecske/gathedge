package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.components.AppShell
import gathedge.frontend.i18n.I18n
import gathedge.shared.i18n.UiKeys

/** The landing page, and a placeholder: it is what `Page.Home` renders and the first screen a project built from this
  * skeleton replaces. Public, like the vocabulary — it's the navbar's own link, always shown, so it must render for a
  * signed-out visitor too.
  *
  * Deliberately the smallest page in the codebase — it exists so the router, the shell and the auth guard have
  * something to point at, not as an example of how a page is written. For that, see `SettingsPage` (a form against
  * `ApiClient`) or `AdminUsersPage` (a listing whose whole state lives in the URL).
  */
object HomePage {

  def render(): HtmlElement = {
    AppShell.render(
      Page.Home,
      div(
        cls := "p-4",
        div(
          cls := "card bg-base-100 shadow-xl max-w-2xl",
          div(
            cls := "card-body",
            h1(cls := "card-title", I18n.t(UiKeys.homeTitle)),
            p(I18n.t(UiKeys.homeBody)),
          ),
        ),
      ),
    )
  }
}
