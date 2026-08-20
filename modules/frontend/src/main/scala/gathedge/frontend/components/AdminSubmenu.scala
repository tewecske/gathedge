package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.I18n
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.i18n.UiKeys

/** Sub-navigation across the five administrator screens.
  *
  * The navbar keeps one "Admin" link, pointing at the user list; these tabs are how the audit log, the usage screen,
  * the system overview and the word-forms diagnostics are reached, so none needs a navbar entry of its own for a screen
  * most sessions never open.
  *
  * `Page.AdminUserDetail` counts as the Users tab: it is reached from that list and returns to it.
  */
object AdminSubmenu {
  def render(active: Page): HtmlElement = {
    div(
      cls := "tabs tabs-boxed mb-4 w-fit",
      // Each tab links to its screen's default view — the plain path, with no listing state on it.
      tabLink(Page.Admin(), I18n.t(UiKeys.navAdminUsers), isUsersTab(active)),
      tabLink(Page.AdminAudit(), I18n.t(UiKeys.adminAuditTitle), isAuditTab(active)),
      tabLink(Page.AdminUsage, I18n.t(UiKeys.navAdminUsage), active == Page.AdminUsage),
      tabLink(Page.AdminSystem, I18n.t(UiKeys.navAdminSystem), active == Page.AdminSystem),
      tabLink(Page.AdminWordForms, I18n.t(UiKeys.navAdminWordForms), active == Page.AdminWordForms),
    )
  }

  /** The two listings are matched by type rather than by value: they carry their paging and filtering, and a tab that
    * went dark as soon as somebody sorted a column would be answering the wrong question.
    */
  private def isUsersTab(active: Page): Boolean = {
    active match {
      case Page.Admin(_) | Page.AdminUserDetail(_) =>
        true
      case _                                       =>
        false
    }
  }

  private def isAuditTab(active: Page): Boolean = {
    active match {
      case Page.AdminAudit(_) =>
        true
      case _                  =>
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
