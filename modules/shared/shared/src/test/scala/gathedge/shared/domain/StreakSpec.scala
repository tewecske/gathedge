package gathedge.shared.domain

import zio.test.*

object StreakSpec extends ZIOSpecDefault {

  def spec = {
    suite("Streak")(
      test("dayOf floors to the UTC day, including before the epoch") {
        assertTrue(
          Streak.dayOf(0L) == 0L,
          Streak.dayOf(86_399_999L) == 0L,
          Streak.dayOf(86_400_000L) == 1L,
          Streak.dayOf(-1L) == -1L,
        )
      },
      test("stateOn: today and yesterday are active, up to two missed days are at risk, then inactive") {
        assertTrue(
          Streak.stateOn(None, 10) == StreakState.Inactive,
          Streak.stateOn(Some(10), 10) == StreakState.Active,
          Streak.stateOn(Some(9), 10) == StreakState.Active,
          Streak.stateOn(Some(8), 10) == StreakState.AtRisk,
          Streak.stateOn(Some(7), 10) == StreakState.AtRisk,
          Streak.stateOn(Some(6), 10) == StreakState.Inactive,
        )
      },
      test("afterPlay: first play, same day, next day, within the grace, beyond it") {
        assertTrue(
          Streak.afterPlay(0, None, 10) == 1,
          Streak.afterPlay(4, Some(10), 10) == 4,
          Streak.afterPlay(4, Some(9), 10) == 5,
          Streak.afterPlay(4, Some(7), 10) == 5,
          Streak.afterPlay(4, Some(6), 10) == 1,
        )
      },
    )
  }
}
