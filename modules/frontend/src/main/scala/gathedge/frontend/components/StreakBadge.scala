package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{ApiClient, ApiError}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.domain.StreakState
import gathedge.shared.dto.StreakResponse
import gathedge.shared.i18n.UiKeys

/** The daily streak in the navbar, left of the language picker. Drawn only for a signed-in account.
  *
  *   - Active: a rocket and the number, tooltip "X days streak".
  *   - AtRisk: the same, plus a shield-exclamation, tooltip "Save your streak".
  *   - Inactive: no number, an academic cap, tooltip "Start learning".
  *
  * It fetches on mount. Every page builds its own shell, so a play that just started is already counted by the time the
  * next page draws this. A failed fetch draws nothing: the streak is decoration, never a reason to show an error.
  */
object StreakBadge {

  def render(): HtmlElement = {
    div(
      cls := "contents",
      child.maybe <-- AppState.currentUserSignal.map(_.isDefined).distinct.flatMapSwitch { signedIn =>
        if (signedIn) ApiClient.streak.map(_.toOption.map(renderFor)) else EventStream.fromValue(None)
      },
    )
  }

  private def renderFor(streak: StreakResponse): HtmlElement = {
    streak.state match {
      case StreakState.Active   =>
        badge(I18n.plural(UiKeys.streakDays, streak.current.toLong), StreakIcons.rocketLaunch(), Some(streak.current))
      case StreakState.AtRisk   =>
        badge(
          I18n.t(UiKeys.streakSave),
          StreakIcons.rocketLaunch(),
          Some(streak.current),
          Some(StreakIcons.shieldExclamation("size-4 text-warning")),
        )
      case StreakState.Inactive =>
        badge(I18n.t(UiKeys.streakStart), StreakIcons.academicCap(), None)
    }
  }

  private def badge(
    tip: String,
    icon: SvgElement,
    number: Option[Int],
    extra: Option[SvgElement] = None,
  ): HtmlElement = {
    div(
      cls                := "tooltip tooltip-bottom",
      dataAttr("tip")    := tip,
      dataAttr("streak") := (if (number.isDefined) "on" else "off"),
      aria.label         := tip,
      div(
        cls := "flex items-center gap-1 px-2 text-sm font-semibold",
        icon,
        number.map(n => span(n.toString)),
        extra.toList,
      ),
    )
  }
}
