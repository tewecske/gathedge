package gathedge.backend.service

import zio.*
import zio.test.*

object RateLimiterSpec extends ZIOSpecDefault {

  private def limiter(window: Duration, guestMax: Int): UIO[InMemoryRateLimiter] = {
    Ref.make(Map.empty[String, Vector[Long]]).map(InMemoryRateLimiter(_, window, 5, guestMax))
  }

  def spec: Spec[Any, Any] = {
    suite("RateLimiter")(
      test("a guest key blocks at its own budget, other keys at the default") {
        for {
          rl      <- limiter(15.minutes, guestMax = 2)
          _       <- ZIO.foreachDiscard(1 to 2)(_ => rl.recordFailure(RateLimitKey.guest("10.0.0.1")))
          _       <- ZIO.foreachDiscard(1 to 2)(_ => rl.recordFailure(RateLimitKey.ip("10.0.0.1")))
          guest   <- rl.isBlocked(RateLimitKey.guest("10.0.0.1"))
          ip      <- rl.isBlocked(RateLimitKey.ip("10.0.0.1"))
          entries <- rl.snapshot
        } yield assertTrue(
          guest,
          !ip,
          entries.find(_.key.startsWith("guest:")).map(e => (e.maxAttempts, e.blocked)) == Some((2, true)),
          entries.find(_.key.startsWith("ip:")).map(e => (e.maxAttempts, e.blocked)) == Some((5, false)),
        )
      },
      test("failures age out after the configured window") {
        for {
          rl     <- limiter(2.minutes, guestMax = 1)
          key     = RateLimitKey.guest("10.0.0.2")
          _      <- rl.recordFailure(key)
          before <- rl.isBlocked(key)
          _      <- TestClock.adjust(3.minutes)
          after  <- rl.isBlocked(key)
        } yield assertTrue(rl.window == 2.minutes, before, !after)
      },
    )
  }
}
