package gathedge.frontend.state

import gathedge.shared.domain.WordLanguage
import zio.test._

object GameSetupSeedSpec extends ZIOSpecDefault {

  def spec = suite("GameSetupSeed")(
    test("a seed is handed over once") {
      val seed   = GameSetupSeed(WordLanguage.De, WordLanguage.Hu, Set(1L, 2L))
      GameSetupSeed.set(seed)
      val first  = GameSetupSeed.take()
      val second = GameSetupSeed.take()
      assertTrue(first.contains(seed), second.isEmpty)
    },
    test("nothing is handed over when nothing was set") {
      assertTrue(GameSetupSeed.take().isEmpty)
    },
  )
}
