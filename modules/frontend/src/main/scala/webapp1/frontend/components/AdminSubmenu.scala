package webapp1.frontend.components

import com.raquo.laminar.api.L._
import webapp1.frontend.i18n.I18n
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.i18n.UiKeys

/** Sub-navigation across the three administrator screens, in the same shape as [[GroupSubmenu]].
  *
  * The navbar keeps one "Admin" link, pointing at the user list; these tabs are how the audit log and the system
  * overview are reached, so neither needs a navbar entry of its own for a screen most sessions never open.
  *
  * `Page.AdminUserDetail` counts as the Users tab: it is reached from that list and returns to it.
  */
object AdminSubmenu {
  def render(active: Page): HtmlElement = {
    div(
      cls := "tabs tabs-boxed mb-4 w-fit",
      tabLink(Page.Admin, I18n.t(UiKeys.navAdminUsers), isUsersTab(active)),
      tabLink(Page.AdminAudit, I18n.t(UiKeys.adminAuditTitle), active == Page.AdminAudit),
      tabLink(Page.AdminSystem, I18n.t(UiKeys.navAdminSystem), active == Page.AdminSystem),
    )
  }

  private def isUsersTab(active: Page): Boolean = {
    active match {
      case Page.Admin | Page.AdminUserDetail(_) =>
        true
      case _                                    =>
        false
    }
  }

  private def tabLink(page: Page, label: String, selected: Boolean): HtmlElement = {
    a(
      cls := "tab" + (
        if (selected)
          " tab-active"
        else
          ""
      ),
      AppRouter.router.navigateTo(page),
      label,
    )
  }
}
