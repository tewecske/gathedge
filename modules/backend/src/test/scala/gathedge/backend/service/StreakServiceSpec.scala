package gathedge.backend.service

import gathedge.backend.TestDataSource
import gathedge.backend.db.{StreakRepository, UserRepository}
import gathedge.shared.domain.StreakState
import zio.*
import zio.test.*

/** Daily streaks against Postgres, with the clock moved by whole days. */
object StreakServiceSpec extends ZIOSpecDefault {

  private val layer = {
    (TestDataSource.postgres >>> (UserRepository.live ++ StreakRepository.live)) >+> StreakService.live
  }

  private def newUser(): RIO[UserRepository, Long] = UserRepository.insertGuest("light", "en", 0L, None).map(_.id)

  private val day = 1.day

  /** The test clock starts at the epoch, which is the very start of a UTC day. */
  private def playOnDays(userId: Long, days: List[Int]): RIO[StreakService, Unit] = {
    ZIO.foreachDiscard(days.sorted.distinct) { d =>
      TestClock.setTime(java.time.Instant.ofEpochMilli(d * 86_400_000L + 3_600_000L)) *> StreakService.recordPlay(
        userId
      )
    }
  }

  private def readOnDay(userId: Long, d: Int): RIO[StreakService, gathedge.shared.dto.StreakResponse] = {
    TestClock.setTime(java.time.Instant.ofEpochMilli(d * 86_400_000L + 7_200_000L)) *> StreakService.current(userId)
  }

  def spec = {
    suite("StreakService")(
      test("an account that never played has no streak") {
        for {
          id     <- newUser()
          streak <- readOnDay(id, 10)
        } yield assertTrue(streak.current == 0, streak.state == StreakState.Inactive, streak.totalDays == 0)
      },
      test("playing on consecutive days extends the streak") {
        for {
          id     <- newUser()
          _      <- playOnDays(id, List(10, 11, 12))
          streak <- readOnDay(id, 12)
        } yield assertTrue(
          streak.current == 3,
          streak.longest == 3,
          streak.totalDays == 3,
          streak.state == StreakState.Active,
        )
      },
      test("playing twice on one day counts once") {
        for {
          id     <- newUser()
          _      <- playOnDays(id, List(10))
          _      <- StreakService.recordPlay(id)
          streak <- readOnDay(id, 10)
        } yield assertTrue(streak.current == 1, streak.totalDays == 1)
      },
      test("a streak is still active the day after the last play") {
        for {
          id     <- newUser()
          _      <- playOnDays(id, List(10, 11))
          streak <- readOnDay(id, 12)
        } yield assertTrue(streak.state == StreakState.Active, streak.current == 2)
      },
      test("one or two missed days leave the streak at risk, and the next play continues it") {
        for {
          id      <- newUser()
          _       <- playOnDays(id, List(10, 11))
          oneGap  <- readOnDay(id, 13)
          twoGap  <- readOnDay(id, 14)
          _       <- playOnDays(id, List(14))
          resumed <- readOnDay(id, 14)
        } yield assertTrue(
          oneGap.state == StreakState.AtRisk,
          oneGap.current == 2,
          twoGap.state == StreakState.AtRisk,
          resumed.state == StreakState.Active,
          resumed.current == 3,
        )
      },
      test("more than two missed days end the streak, and the next play restarts it at one") {
        for {
          id      <- newUser()
          _       <- playOnDays(id, List(10, 11, 12))
          lapsed  <- readOnDay(id, 16)
          _       <- playOnDays(id, List(16))
          restart <- readOnDay(id, 16)
        } yield assertTrue(
          lapsed.state == StreakState.Inactive,
          lapsed.current == 0,
          lapsed.longest == 3,
          restart.current == 1,
          restart.longest == 3,
          restart.totalDays == 4,
        )
      },
      test("streaks belong to one account each") {
        for {
          first  <- newUser()
          second <- newUser()
          _      <- playOnDays(first, List(10, 11))
          other  <- readOnDay(second, 11)
        } yield assertTrue(other.current == 0, other.totalDays == 0)
      },
    ).provide(layer)
  }
}
