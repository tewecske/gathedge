package webapp1.frontend.components

import com.raquo.laminar.api.L._
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.GroupRole

/** Sub-navigation shown on group-scoped pages (overview / members). The "Members" tab (and the page it links to) is
  * admin-only, since group membership management is admin-only backend-side too.
  */
object GroupSubmenu {
  def render(groupId: Long, active: Page, myRole: GroupRole): HtmlElement = {
    val tabs = {
      List(tabLink(Page.GroupDetail(groupId), active, "Overview")) ++ (
        if (myRole.isAdmin)
          List(tabLink(Page.GroupMembers(groupId), active, "Members"))
        else
          Nil
      )
    }
    div(cls := "tabs tabs-boxed mb-4 w-fit", tabs)
  }

  private def tabLink(page: Page, active: Page, label: String): HtmlElement = {
    a(
      cls := "tab" + (
        if (page == active)
          " tab-active"
        else
          ""
      ),
      AppRouter.router.navigateTo(page),
      label,
    )
  }
}
