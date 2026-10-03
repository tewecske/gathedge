package gathedge.shared.domain

import zio.test.*

object AchievementSpec extends ZIOSpecDefault {

  private val games = Achievements.gamesPlayed.ladder

  def spec = {
    suite("Achievements")(
      test("tierFor follows the opening ladder rung by rung") {
        assertTrue(
          games.tierFor(0) == 0,
          games.tierFor(1) == 1,
          games.tierFor(4) == 1,
          games.tierFor(5) == 2,
          games.tierFor(10) == 3,
          games.tierFor(25) == 4,
          games.tierFor(50) == 5,
          games.tierFor(75) == 6,
          games.tierFor(99) == 6,
          games.tierFor(100) == 7,
        )
      },
      test("after the ladder, each further step is one more tier") {
        assertTrue(
          games.tierFor(149) == 7,
          games.tierFor(150) == 8,
          games.tierFor(200) == 9,
          games.threshold(8) == Some(150),
          games.threshold(9) == Some(200),
          Achievements.perfectGames.ladder.threshold(8) == Some(75),
          Achievements.longestStreak.ladder.threshold(6) == Some(60),
          Achievements.daysPlayed.ladder.threshold(4) == Some(100),
        )
      },
      test("large counts keep earning tiers, and threshold is the inverse of tierFor") {
        val count = 1_000_000
        val tier  = games.tierFor(count)
        assertTrue(
          tier == 7 + (count - 100) / 50,
          games.threshold(tier).exists(_ <= count),
          games.threshold(tier + 1).exists(_ > count),
        )
      },
      test("threshold matches the ladder values, and is None below tier 1") {
        assertTrue(
          (1 to 7).toList.map(games.threshold) == List(1, 5, 10, 25, 50, 75, 100).map(Some(_)),
          games.threshold(0).isEmpty,
        )
      },
      test("a one-off has one tier and no next threshold") {
        val polyglot = Achievements.polyglot.ladder
        assertTrue(
          polyglot.isOneOff,
          polyglot.tierFor(1) == 0,
          polyglot.tierFor(2) == 1,
          polyglot.tierFor(9) == 1,
          polyglot.threshold(1) == Some(2),
          polyglot.threshold(2).isEmpty,
          !games.isOneOff,
        )
      },
      test("the catalog has one games-played achievement per game type, and unique codes") {
        val codes = Achievements.all.map(_.code)
        assertTrue(
          GameMode.all.forall(mode => Achievements.all.exists(_.mode.contains(mode))),
          codes.distinct == codes,
          Achievements.byCode("gamesPlayed.typing").flatMap(_.mode) == Some(GameMode.Typing),
        )
      },
      test("a play qualifies only when finished and at least ten words long") {
        assertTrue(
          !Achievements.isQualifying(finished = true, 9),
          !Achievements.isQualifying(finished = false, 10),
          Achievements.isQualifying(finished = true, 10),
        )
      },
    )
  }
}
