package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{Achievement, Achievements}
import gathedge.shared.dto.{AchievementUnlock, XpGain}
import gathedge.shared.i18n.UiKeys

/** The daisyUI modal that shows the tiers one play unlocked, one tier per page, with fireworks. Each page also shows
  * the XP the play gave. When the play raised the account's level, a last page says so.
  *
  * The answer that finishes a play carries the unlocks and the XP (see `SubmitAnswerResponse`), so the dialog needs no
  * request of its own. A page makes one instance, puts [[render]] once in its tree, and calls [[open]] when the results
  * arrive.
  *
  * A `div.modal` with `modal-open`, like [[ConfirmDialog]], and not a `<dialog>`: a `<dialog>` opened as a modal goes
  * to the browser's top layer, and no z-index can then put the fireworks canvas over it.
  *
  * The fireworks start when the dialog opens and again on each "Next". The bursts of the page before stop first. Close,
  * the backdrop, the link to the tab and an unmount all stop them.
  */
final class AchievementUnlockDialog(fireworks: AchievementUnlockDialog.Fireworks) {

  import AchievementUnlockDialog.DialogPage

  /** The pages to show, the page on screen, and the XP the play gave. `None` when the dialog is closed. */
  private val stateVar: Var[Option[(List[DialogPage], Int, Option[XpGain])]] = Var(None)

  private var stopFireworks: () => Unit = () => ()

  /** Opens the dialog on the first page. Does nothing when there is nothing to show: no tier this build knows, and no
    * new level.
    */
  def open(unlocks: List[AchievementUnlock], xp: Option[XpGain]): Unit = {
    val pages = AchievementUnlockDialog.pages(unlocks, xp)
    if (pages.nonEmpty) {
      stateVar.set(Some((pages, 0, xp)))
      restartFireworks()
    }
  }

  private def next(): Unit = {
    stateVar.update(_.map { case (pages, index, xp) => (pages, (index + 1).min(pages.size - 1), xp) })
    restartFireworks()
  }

  private def close(): Unit = {
    halt()
    stateVar.set(None)
  }

  private def restartFireworks(): Unit = {
    halt()
    stopFireworks = fireworks()
  }

  private def halt(): Unit = {
    stopFireworks()
    stopFireworks = () => ()
  }

  def render(): HtmlElement = {
    div(
      cls  := "modal",
      cls("modal-open") <-- stateVar.signal.map(_.isDefined),
      role := "dialog",
      child.maybe <-- stateVar.signal.map(_.map { case (pages, index, xp) => renderBox(pages, index, xp) }),
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => close())),
      onUnmountCallback(_ => halt()),
    )
  }

  private def renderBox(pages: List[DialogPage], index: Int, xp: Option[XpGain]): HtmlElement = {
    val last = index == pages.size - 1
    div(
      cls := "modal-box w-full max-w-sm flex flex-col items-center gap-3 text-center",
      pages(index) match {
        case DialogPage.Tier(achievement, unlock) =>
          renderTier(achievement, unlock)
        case DialogPage.LevelUp(level)            =>
          renderLevelUp(level)
      },
      xp.filter(_.gained > 0) match {
        case Some(gain) =>
          span(cls := "badge badge-warning badge-lg", I18n.t(UiKeys.levelsGained, gain.gained))
        case None       =>
          emptyNode
      },
      if (pages.size > 1) {
        p(cls := "text-xs opacity-60", I18n.t(UiKeys.achievementsDialogPage, index + 1, pages.size))
      } else {
        emptyNode
      },
      div(
        cls := "modal-action w-full justify-between",
        a(
          cls := "btn btn-sm btn-ghost",
          AppRouter.router.navigateTo(Page.ProfileAchievements),
          I18n.t(UiKeys.achievementsViewAll),
          onClick --> Observer[Any](_ => halt()),
        ),
        if (last) {
          button(
            cls := "btn btn-sm btn-primary",
            typ := "button",
            I18n.t(UiKeys.achievementsDialogClose),
            onClick.mapToUnit --> Observer[Unit](_ => close()),
            onMountFocus,
          )
        } else {
          button(
            cls := "btn btn-sm btn-primary",
            typ := "button",
            I18n.t(UiKeys.achievementsDialogNext),
            onClick.mapToUnit --> Observer[Unit](_ => next()),
            onMountFocus,
          )
        },
      ),
    )
  }

  private def renderTier(achievement: Achievement, unlock: AchievementUnlock): Modifier[HtmlElement] = {
    List(
      h3(cls  := "font-bold text-lg", I18n.t(UiKeys.achievementsDialogTitle)),
      div(cls := "text-warning", StreakIcons.trophy("size-16")),
      p(cls   := "text-xl font-semibold", Labels.achievementName(achievement)),
      if (achievement.ladder.isOneOff) {
        emptyNode
      } else {
        span(cls := "badge badge-primary", I18n.t(UiKeys.achievementsTier, unlock.tier))
      },
      p(cls   := "text-sm opacity-70", Labels.achievementHint(achievement)),
      renderCounts(achievement, unlock),
    )
  }

  private def renderLevelUp(level: Int): Modifier[HtmlElement] = {
    List(
      h3(cls  := "font-bold text-lg", I18n.t(UiKeys.levelsLevelUpTitle)),
      div(cls := "text-warning", StreakIcons.rocketLaunch("size-16")),
      p(cls   := "text-xl font-semibold", I18n.t(UiKeys.levelsLevelUpText, level)),
    )
  }

  /** The count the tier needs and the count of the tier after it. A one-off has no tier after it, so it shows neither.
    */
  private def renderCounts(achievement: Achievement, unlock: AchievementUnlock): Modifier[HtmlElement] = {
    (achievement.ladder.threshold(unlock.tier), achievement.ladder.threshold(unlock.tier + 1)) match {
      case (Some(reached), Some(next)) if !achievement.ladder.isOneOff =>
        div(
          cls := "flex flex-col items-center gap-1 text-sm",
          span(I18n.t(UiKeys.achievementsDialogReached, reached)),
          span(I18n.t(UiKeys.achievementsDialogNextTier, next)),
        )
      case _                                                           =>
        emptyNode
    }
  }
}

object AchievementUnlockDialog {

  /** Starts the fireworks and gives back the function that stops them. `App` passes the real one (`Fireworks.launch`);
    * a spec passes a stub, which keeps the `canvas-confetti` import out of its graph.
    */
  type Fireworks = () => (() => Unit)

  /** One page of the dialog: a tier the play unlocked, or the new level the play reached. */
  enum DialogPage {
    case Tier(achievement: Achievement, unlock: AchievementUnlock)
    case LevelUp(level: Int)
  }

  /** The tier pages, in catalog order and lowest tier first, then a level-up page when the play raised the level. A
    * code this build does not know gets no page.
    */
  private[frontend] def pages(unlocks: List[AchievementUnlock], xp: Option[XpGain]): List[DialogPage] = {
    val tiers   = unlocks
      .flatMap(unlock => Achievements.byCode(unlock.code).map(achievement => (achievement, unlock)))
      .sortBy { case (achievement, unlock) => (Achievements.all.indexOf(achievement), unlock.tier) }
      .map { case (achievement, unlock) => DialogPage.Tier(achievement, unlock) }
    val levelUp =
      xp.filter(gain => gain.level.level > gain.levelBefore).map(gain => DialogPage.LevelUp(gain.level.level))
    tiers ++ levelUp.toList
  }
}
