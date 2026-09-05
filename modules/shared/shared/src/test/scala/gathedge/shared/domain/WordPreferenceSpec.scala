package gathedge.shared.domain

import zio.test._

object WordPreferenceSpec extends ZIOSpecDefault {
  def spec = {
    suite("WordPreference")(
      test("code round-trips through fromString for every case") {
        assertTrue(WordPreference.all.forall(p => WordPreference.fromString(WordPreference.code(p)).contains(p)))
      },
      test("code is the stable wire string, not toString") {
        assertTrue(
          WordPreference.code(WordPreference.All) == "all",
          // Deliberate: `LeastPlayed` keeps the code it was written under, so the plays already recorded as
          // "unplayed" keep reading back as the option they were played with.
          WordPreference.code(WordPreference.LeastPlayed) == "unplayed",
          WordPreference.code(WordPreference.MostMistakes) == "mostMistakes",
        )
      },
      test("fromString is case-insensitive and unknown strings answer None") {
        assertTrue(
          WordPreference.fromString("ALL").contains(WordPreference.All),
          WordPreference.fromString("bogus").isEmpty,
        )
      },
    )
  }
}
