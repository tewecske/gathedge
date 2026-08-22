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
          WordPreference.code(WordPreference.Unplayed) == "unplayed",
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
