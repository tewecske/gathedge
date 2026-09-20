package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.I18n
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.i18n.UiKeys

/** Sub-navigation across the profile's three tabs: the daily streak, the play history and the accounts that share their
  * progress with the reader.
  *
  * Built like `AdminSubmenu`. `Page.SharedPlayerHistory` counts as the "Shared with me" tab: it is reached from that
  * list and returns to it.
  */
object ProfileSubmenu {
  def render(active: Page): HtmlElement = {
    div(
      cls := "tabs tabs-boxed mb-4 w-fit",
      // Each tab links to its screen's default view — the plain path, with no listing state on it.
      tabLink(Page.Profile, I18n.t(UiKeys.profileStreakCard), active == Page.Profile),
      tabLink(Page.MyPlays(), I18n.t(UiKeys.profileTabHistory), isHistoryTab(active)),
      tabLink(Page.SharedProgress, I18n.t(UiKeys.profileTabShared), isSharedTab(active)),
    )
  }

  /** Matched by type: the history carries its paging and filtering, and a tab that went dark as soon as somebody sorted
    * a column would be answering the wrong question.
    */
  private def isHistoryTab(active: Page): Boolean = {
    active match {
      case Page.MyPlays(_) =>
        true
      case _               =>
        false
    }
  }

  private def isSharedTab(active: Page): Boolean = {
    active match {
      case Page.SharedProgress | Page.SharedPlayerHistory(_, _) =>
        true
      case _                                                    =>
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
