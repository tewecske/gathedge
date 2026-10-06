package gathedge.shared.domain

import zio.test.*

object LevelsSpec extends ZIOSpecDefault {

  def spec = {
    suite("Levels")(
      test("a tier gives its weight times the count since the previous tier") {
        val games   = Achievements.gamesPlayed
        val perfect = Achievements.perfectGames
        assertTrue(
          games.xpFor(1) == 8,
          games.xpFor(2) == 32,
          games.xpFor(7) == 200,
          // After the opening ladder every tier is one step of 50 plays.
          games.xpFor(8) == 400,
          games.xpFor(30) == 400,
          perfect.xpFor(1) == 12,
          perfect.xpFor(8) == 300,
          Achievements.gamesPlayedIn(GameMode.Typing).xpFor(1) == 4,
          Achievements.longestStreak.xpFor(1) == 36,
          Achievements.daysPlayed.xpFor(1) == 80,
        )
      },
      test("a one-off gives its XP once, and a tier the ladder does not have gives nothing") {
        assertTrue(
          Achievements.marathon.xpFor(1) == 80,
          Achievements.polyglot.xpFor(1) == 120,
          Achievements.comeback.xpFor(1) == 40,
          Achievements.marathon.xpFor(2) == 0,
          Achievements.gamesPlayed.xpFor(0) == 0,
        )
      },
      test("the first perfect play gives little XP") {
        val tiers =
          List(Achievements.gamesPlayed, Achievements.gamesPlayedIn(GameMode.Typing), Achievements.perfectGames)
        assertTrue(Levels.playXp(1, 10L) + tiers.map(_.xpFor(1)).sum == 39L)
      },
      test("each level costs 100 XP more than the one before") {
        assertTrue(
          Levels.totalFor(1) == 0L,
          Levels.totalFor(2) == 100L,
          Levels.totalFor(3) == 300L,
          Levels.totalFor(5) == 1_000L,
          Levels.totalFor(10) == 4_500L,
          Levels.totalFor(20) == 19_000L,
          (2 to 200).forall(level => Levels.totalFor(level + 1) - Levels.totalFor(level) == 100L * level),
        )
      },
      test("levelFor is right at and next to every boundary") {
        assertTrue(
          Levels.levelFor(-5L) == 1,
          Levels.levelFor(0L) == 1,
          Levels.levelFor(99L) == 1,
          Levels.levelFor(100L) == 2,
          (2 to 2_000).forall { level =>
            val start = Levels.totalFor(level)
            Levels.levelFor(start) == level && Levels.levelFor(start - 1L) == level - 1
          },
          Levels.levelFor(Levels.totalFor(100_000)) == 100_000,
        )
      },
      test("progress names the level and the totals of this level and the next") {
        assertTrue(
          Levels.progress(150L) == LevelProgress(level = 2, totalXp = 150L, levelStartXp = 100L, nextLevelXp = 300L),
          Levels.progress(-1L) == LevelProgress(level = 1, totalXp = 0L, levelStartXp = 0L, nextLevelXp = 100L),
        )
      },
    )
  }
}
