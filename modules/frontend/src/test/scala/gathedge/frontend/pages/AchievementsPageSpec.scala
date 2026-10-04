package gathedge.frontend.pages

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L._
import gathedge.frontend.components.AchievementUnlockDialog
import gathedge.shared.domain.Achievements
import gathedge.shared.dto.{AchievementProgress, AchievementUnlock}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom
import zio.test._

/** The achievements tab and the unlock dialog of the game result screen.
  *
  * jsdom loads no catalog, so `I18n.t` renders the key itself, and the assertions are on keys. There is no backend
  * either: the tab's own request fails, so the rows are checked through `renderList` with a hand-made answer.
  */
object AchievementsPageSpec extends ZIOSpecDefault {

  private def withElement[A](element: => HtmlElement)(use: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val rootNode  = L.render(container, element)
    try {
      use(container)
    } finally {
      rootNode.unmount()
      dom.document.body.removeChild(container)
    }
  }

  private def rows(container: dom.Element): List[dom.Element] = container.querySelectorAll("li.list-row").toList

  private def headingOf(row: dom.Element): String = row.querySelector("h3").textContent

  def spec = {
    suite("AchievementsPage")(
      test("the profile offers the achievements tab next to the streak") {
        val tabs = withElement(AchievementsPage.render()) { container =>
          container.querySelectorAll(".tab").toList.map(_.textContent)
        }

        assertTrue(
          tabs == List(
            UiKeys.profileStreakCard,
            UiKeys.profileTabAchievements,
            UiKeys.profileTabHistory,
            UiKeys.profileTabShared,
          )
        )
      },
      test("every achievement of the catalog has a row, also with an empty answer") {
        val (headings, badges, bars) = withElement(AchievementsPage.renderList(Nil)) { container =>
          (
            rows(container).map(headingOf),
            container.querySelectorAll(".badge").toList.map(_.textContent),
            container.querySelectorAll("progress").length,
          )
        }
        val ladders                  = Achievements.all.count(!_.ladder.isOneOff)
        val oneOffs                  = Achievements.all.count(_.ladder.isOneOff)

        assertTrue(
          headings.size == Achievements.all.size,
          headings.contains(UiKeys.achievementName(Achievements.gamesPlayed.code)),
          headings.contains(UiKeys.achievementName(Achievements.marathon.code)),
          // The per-game-type rows share one name, filled with the game type's label.
          headings.count(_ == UiKeys.achievementsGamesPlayedInName) == Achievements.all.count(_.mode.isDefined),
          badges.count(_ == UiKeys.achievementsNotStarted) == ladders,
          badges.count(_ == UiKeys.achievementsNotDone) == oneOffs,
          bars == ladders,
        )
      },
      test("a row shows its tier, its count against the next threshold, and a one-off its date") {
        val progress                 = List(
          AchievementProgress(Achievements.gamesPlayed.code, 2, 7, Some(10), Some(1000L)),
          AchievementProgress(Achievements.marathon.code, 1, 1, None, Some(1000L)),
          AchievementProgress("noSuchAchievement", 3, 3, Some(4), None),
        )
        val (count, games, marathon) = withElement(AchievementsPage.renderList(progress)) { container =>
          val byHeading = rows(container).map(row => headingOf(row) -> row).toMap
          (
            rows(container).size,
            byHeading(UiKeys.achievementName(Achievements.gamesPlayed.code)),
            byHeading(UiKeys.achievementName(Achievements.marathon.code)),
          )
        }
        val bar                      = games.querySelector("progress")

        assertTrue(
          count == Achievements.all.size,
          games.querySelector(".badge").textContent == UiKeys.achievementsTier,
          bar.getAttribute("max") == "10",
          games.textContent.contains(UiKeys.achievementsProgress),
          marathon.querySelector(".badge").textContent == UiKeys.achievementsDoneOn,
          marathon.querySelectorAll("progress").length == 0,
        )
      },
      test("the unlock dialog shows one tier per page, with fireworks on open and on each Next") {
        var started = 0
        var stopped = 0
        val dialog  = new AchievementUnlockDialog(() => {
          started += 1
          () => stopped += 1
        })
        val unlocks = List(
          AchievementUnlock(Achievements.marathon.code, 1, 1000L),
          AchievementUnlock("noSuchAchievement", 1, 1000L),
          AchievementUnlock(Achievements.gamesPlayed.code, 1, 1000L),
        )

        withElement(dialog.render()) { container =>
          def isOpen: Boolean            = container.querySelector(".modal").classList.contains("modal-open")
          def text: String               = container.textContent
          def buttons: List[String]      = container.querySelectorAll("button").toList.map(_.textContent)
          def press(label: String): Unit = {
            container
              .querySelectorAll("button")
              .toList
              .find(_.textContent == label)
              .foreach(_.asInstanceOf[dom.html.Element].click())
          }

          val closedAtFirst = !isOpen && started == 0
          dialog.open(unlocks)
          // Catalog order: the ladder first, with its tier and its counts, then the one-off.
          val first         = (
            isOpen,
            text.contains(UiKeys.achievementsDialogTitle),
            text.contains(UiKeys.achievementName(Achievements.gamesPlayed.code)),
            text.contains(UiKeys.achievementsTier),
            text.contains(UiKeys.achievementsDialogReached),
            text.contains(UiKeys.achievementsDialogNextTier),
            text.contains(UiKeys.achievementsDialogPage),
            text.contains(UiKeys.achievementsViewAll),
            buttons == List(UiKeys.achievementsDialogNext),
            started == 1,
          )
          press(UiKeys.achievementsDialogNext)
          val second        = (
            text.contains(UiKeys.achievementName(Achievements.marathon.code)),
            !text.contains(UiKeys.achievementsTier),
            !text.contains(UiKeys.achievementsDialogNextTier),
            buttons == List(UiKeys.achievementsDialogClose),
            started == 2,
            stopped == 1,
          )
          press(UiKeys.achievementsDialogClose)
          val closed        = (!isOpen, stopped == 2)

          assertTrue(
            closedAtFirst,
            first == (true, true, true, true, true, true, true, true, true, true),
            second == (true, true, true, true, true, true),
            closed == (true, true),
          )
        }
      },
      test("the unlock dialog stays shut when a play unlocked nothing this build knows") {
        var started = 0
        val dialog  = new AchievementUnlockDialog(() => {
          started += 1
          () => ()
        })

        // Read inside `withElement`: the unmount at its end takes the modal out of the container.
        val open = withElement(dialog.render()) { container =>
          dialog.open(Nil)
          dialog.open(List(AchievementUnlock("noSuchAchievement", 1, 1000L)))
          container.querySelector(".modal").classList.contains("modal-open")
        }

        assertTrue(!open, started == 0)
      },
    )
  }
}
