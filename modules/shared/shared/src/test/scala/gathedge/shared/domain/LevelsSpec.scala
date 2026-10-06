package gathedge.shared.domain

import zio.test.*

object LevelsSpec extends ZIOSpecDefault {

  def spec = {
    suite("Levels")(
      test("a tier gives its base XP times its number") {
        assertTrue(
          Achievements.gamesPlayed.xpFor(1) == 25,
          Achievements.gamesPlayed.xpFor(2) == 50,
          Achievements.gamesPlayed.xpFor(30) == 750,
          Achievements.gamesPlayedIn(GameMode.Typing).xpFor(1) == 25,
          Achievements.perfectGames.xpFor(1) == 100,
          Achievements.perfectGames.xpFor(3) == 300,
          Achievements.longestStreak.xpFor(1) == 100,
          Achievements.daysPlayed.xpFor(2) == 160,
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
      test("an answer gives 4 when correct, 3 for a typo and 1 when wrong") {
        val answers = Map(AnswerOutcome.Correct -> 7L, AnswerOutcome.Typo -> 2L, AnswerOutcome.Wrong -> 1L)
        assertTrue(
          Levels.playXp(1, Map.empty) == 25L,
          Levels.playXp(1, answers) == 25L + 28L + 6L + 1L,
          Levels.playXp(3, Map.empty) == 75L,
        )
      },
      test("a first perfect play reaches level 2") {
        val tiers =
          List(Achievements.gamesPlayed, Achievements.gamesPlayedIn(GameMode.Typing), Achievements.perfectGames)
        val xp    = Levels.playXp(1, Map(AnswerOutcome.Correct -> 10L)) + tiers.map(_.xpFor(1)).sum
        assertTrue(xp == 215L, Levels.levelFor(xp) == 2)
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
