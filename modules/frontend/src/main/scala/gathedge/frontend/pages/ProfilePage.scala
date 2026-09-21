package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.components.{Alert, AppShell, ProfileSubmenu, StreakIcons}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.StreakState
import gathedge.shared.dto.StreakResponse
import gathedge.shared.i18n.UiKeys

object ProfilePage {
  def render(): HtmlElement =
    AppShell.render(Page.Profile, ProfileSubmenu.layout(Page.Profile, new ProfilePage().render()))
}

/** The account's daily streak and play totals. */
private class ProfilePage {
  private val streakVar: Var[Option[StreakResponse]] = Var(None)
  private val errorVar: Var[Option[String]]          = Var(None)

  def render(): HtmlElement = {
    div(
      cls := "flex flex-col gap-6",
      child.maybe <-- errorVar.signal.map(_.map(message => Alert.error(message))),
      child.maybe <-- streakVar.signal.map(_.map(renderStreak)),
      ApiClient.streak --> Observer[Either[ApiError, StreakResponse]] {
        case Right(streak) =>
          streakVar.set(Some(streak))
        case Left(err)     =>
          errorVar.set(Some(err.message))
      },
    )
  }

  private def renderStreak(streak: StreakResponse): HtmlElement = {
    val (icon, hint) = streak.state match {
      case StreakState.Active   =>
        (StreakIcons.rocketLaunch("size-8"), I18n.t(UiKeys.profileActiveHint))
      case StreakState.AtRisk   =>
        (StreakIcons.shieldExclamation("size-8 text-warning"), I18n.t(UiKeys.profileSaveHint))
      case StreakState.Inactive =>
        (StreakIcons.academicCap("size-8"), I18n.t(UiKeys.profileStartHint))
    }
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        h2(cls  := "card-title text-lg", I18n.t(UiKeys.profileStreakCard)),
        div(cls := "flex items-center gap-3", icon, p(hint)),
        div(
          cls   := "stats stats-vertical sm:stats-horizontal shadow",
          stat(I18n.t(UiKeys.profileCurrent), streak.current),
          stat(I18n.t(UiKeys.profileLongest), streak.longest),
          stat(I18n.t(UiKeys.profileTotalDays), streak.totalDays),
        ),
      ),
    )
  }

  private def stat(title: String, value: Int): HtmlElement = {
    div(
      cls := "stat",
      div(cls := "stat-title", title),
      div(cls := "stat-value", value.toString),
    )
  }
}
