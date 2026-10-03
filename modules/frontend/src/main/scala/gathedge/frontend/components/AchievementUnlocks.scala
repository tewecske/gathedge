package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.Achievements
import gathedge.shared.dto.AchievementUnlock
import gathedge.shared.i18n.UiKeys

/** The tiers one play unlocked, for the game result screen. The answer that finishes a play carries them (see
  * `SubmitAnswerResponse`), so the screen needs no extra request.
  */
object AchievementUnlocks {

  /** `None` when the play unlocked nothing, or only codes this build does not know. A play that crosses two rungs of
    * one ladder shows both, lowest first.
    */
  def render(unlocks: List[AchievementUnlock]): Option[HtmlElement] = {
    val lines =
      unlocks.sortBy(unlock => (Achievements.all.indexWhere(_.code == unlock.code), unlock.tier)).flatMap(line)
    Option.when(lines.nonEmpty) {
      div(
        cls  := "alert alert-success flex flex-col items-start gap-1",
        role := "status",
        div(
          cls  := "flex items-center gap-2 font-semibold",
          StreakIcons.trophy(),
          span(I18n.t(UiKeys.achievementsUnlockedTitle)),
        ),
        ul(cls := "list-disc list-inside text-sm", lines.map(text => li(text))),
        a(
          cls  := "link text-sm",
          AppRouter.router.navigateTo(Page.ProfileAchievements),
          I18n.t(UiKeys.achievementsViewAll),
        ),
      )
    }
  }

  /** A one-off has one tier only, so its name says it all. */
  private def line(unlock: AchievementUnlock): Option[String] = {
    Achievements.byCode(unlock.code).map { achievement =>
      if (achievement.ladder.isOneOff) {
        Labels.achievementName(achievement)
      } else {
        I18n.t(UiKeys.achievementsUnlockedTier, Labels.achievementName(achievement), unlock.tier)
      }
    }
  }
}
