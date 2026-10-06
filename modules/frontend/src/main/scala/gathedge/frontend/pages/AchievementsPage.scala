package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.Page
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.components.{Alert, AppShell, Formats, Labels, ProfileSubmenu, StreakIcons}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{Achievement, Achievements, LevelProgress}
import gathedge.shared.dto.{AchievementProgress, AchievementsResponse}
import gathedge.shared.i18n.UiKeys

object AchievementsPage {

  def render(): HtmlElement = {
    AppShell.render(
      Page.ProfileAchievements,
      ProfileSubmenu.layout(Page.ProfileAchievements, new AchievementsPage().render()),
    )
  }

  /** One row per entry of [[Achievements.all]], in catalog order, also for an achievement the account has not started.
    * The catalog decides the rows, not the answer: an entry the answer leaves out shows as not started, and a code this
    * build does not know is dropped.
    */
  private[frontend] def renderList(progress: List[AchievementProgress]): HtmlElement = {
    val byCode = progress.map(row => row.code -> row).toMap
    ul(
      cls := "list",
      Achievements.all.map(achievement =>
        renderRow(achievement, byCode.getOrElse(achievement.code, notStarted(achievement)))
      ),
    )
  }

  /** The account's level, its XP in this level against what the next level needs, and how much XP is still missing. */
  private[frontend] def renderLevel(level: LevelProgress): HtmlElement = {
    val into   = level.totalXp - level.levelStartXp
    val needed = level.nextLevelXp - level.levelStartXp
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body flex-row flex-wrap items-center gap-4",
        div(cls := "text-warning", StreakIcons.trophy("size-10")),
        div(
          cls   := "flex flex-col gap-1 grow",
          h2(cls := "card-title", I18n.t(UiKeys.levelsLevel, level.level)),
          div(
            cls  := "flex items-center gap-3",
            progressTag(
              cls        := "progress progress-warning w-full max-w-xs",
              value      := into.toString,
              maxAttr    := needed.toString,
              aria.label := I18n.t(UiKeys.levelsLevel, level.level),
            ),
            span(cls     := "text-sm tabular-nums whitespace-nowrap", I18n.t(UiKeys.levelsProgress, into, needed)),
          ),
          p(cls  := "text-sm opacity-70", I18n.t(UiKeys.levelsToNext, needed - into)),
        ),
      ),
    )
  }

  private def notStarted(achievement: Achievement): AchievementProgress = {
    AchievementProgress(achievement.code, 0, 0, achievement.ladder.threshold(1), None)
  }

  private def renderRow(achievement: Achievement, progress: AchievementProgress): HtmlElement = {
    val earned = progress.tier > 0
    li(
      cls := "list-row items-start",
      div(
        cls := (if (earned) "text-warning" else "opacity-30"),
        StreakIcons.trophy("size-8"),
      ),
      div(
        cls := "flex flex-col gap-1",
        div(
          cls := "flex flex-wrap items-center gap-2",
          h3(cls := "font-semibold", Labels.achievementName(achievement)),
          statusBadge(achievement, progress),
        ),
        p(cls := "text-sm opacity-70", Labels.achievementHint(achievement)),
        if (achievement.ladder.isOneOff) emptyNode else progressBar(achievement, progress),
      ),
    )
  }

  /** A one-off says done, with its date, or not done. A ladder says its current tier, or that it is not started. */
  private def statusBadge(achievement: Achievement, progress: AchievementProgress): HtmlElement = {
    if (achievement.ladder.isOneOff) {
      if (progress.tier > 0) {
        span(
          cls := "badge badge-success",
          progress.unlockedAt match {
            case Some(at) =>
              I18n.t(UiKeys.achievementsDoneOn, Formats.date(at))
            case None     =>
              I18n.t(UiKeys.achievementsDone)
          },
        )
      } else {
        span(cls := "badge badge-ghost", I18n.t(UiKeys.achievementsNotDone))
      }
    } else if (progress.tier > 0) {
      span(cls := "badge badge-primary", I18n.t(UiKeys.achievementsTier, progress.tier))
    } else {
      span(cls := "badge badge-ghost", I18n.t(UiKeys.achievementsNotStarted))
    }
  }

  /** The count against the next tier's threshold. A ladder always has a next tier, so `None` only comes from an answer
    * this build does not expect; the bar is then left out.
    */
  private def progressBar(achievement: Achievement, progress: AchievementProgress): Modifier[HtmlElement] = {
    progress.nextThreshold match {
      case Some(next) =>
        div(
          cls := "flex items-center gap-3",
          progressTag(
            cls        := "progress progress-primary w-full max-w-xs",
            value      := progress.count.min(next).toString,
            maxAttr    := next.toString,
            aria.label := Labels.achievementName(achievement),
          ),
          span(
            cls        := "text-sm tabular-nums whitespace-nowrap",
            I18n.t(UiKeys.achievementsProgress, progress.count, next),
          ),
        )
      case None       =>
        emptyNode
    }
  }
}

/** Every achievement of the catalog and how far the account is on each. */
private class AchievementsPage {
  private val responseVar: Var[Option[AchievementsResponse]] = Var(None)
  private val errorVar: Var[Option[String]]                  = Var(None)

  def render(): HtmlElement = {
    div(
      cls := "flex flex-col gap-4",
      Alert.maybeError(errorVar.signal),
      child.maybe <-- responseVar.signal.map(_.map(response => AchievementsPage.renderLevel(response.level))),
      p(cls := "text-sm opacity-70", I18n.t(UiKeys.achievementsIntro, Achievements.qualifyingWordCount)),
      div(
        cls := "card bg-base-100 shadow",
        div(
          cls := "card-body p-2",
          child <-- responseVar.signal.combineWith(errorVar.signal).map {
            case (Some(response), _) =>
              AchievementsPage.renderList(response.achievements)
            // The alert above says what went wrong; a list of rows that all read "not started" would be wrong.
            case (None, Some(_))     =>
              div()
            case (None, None)        =>
              span(cls := "loading loading-spinner m-4")
          },
        ),
      ),
      ApiClient.achievements --> Observer[Either[ApiError, AchievementsResponse]] {
        case Right(response) =>
          responseVar.set(Some(response))
        case Left(err)       =>
          errorVar.set(Some(err.message))
      },
    )
  }
}
