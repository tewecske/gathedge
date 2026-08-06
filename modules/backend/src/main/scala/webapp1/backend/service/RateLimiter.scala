package webapp1.backend.service

import zio.*

import java.util.concurrent.TimeUnit

trait RateLimiter {
  def isBlocked(key: String): UIO[Boolean]
  def recordFailure(key: String): UIO[Unit]

  /** Forgets the failures recorded for a key. Called after a successful authentication so earlier typos don't keep
    * counting towards a lockout for the rest of the window.
    */
  def clear(key: String): UIO[Unit]

  /** Background cleanup loop; fork as a daemon fiber once at startup. */
  def runPruner: URIO[Any, Nothing]
}

object RateLimiter {
  val live: ULayer[RateLimiter] = ZLayer.fromZIO(
    Ref.make(Map.empty[String, Vector[Long]]).map(InMemoryRateLimiter(_))
  )
}

/** Key namespaces. Limiting per account only would let one attacker spray a single password across many accounts
  * untouched, so callers check both dimensions and block if either trips.
  */
object RateLimitKey {
  def email(value: String): String = s"email:${value.trim.toLowerCase}"
  def ip(value: String): String    = s"ip:${value.trim}"

  /** Separate from [[email]] so asking for another verification link cannot burn an account's login budget, nor the
    * other way round.
    */
  def verification(value: String): String = s"verify:${value.trim.toLowerCase}"
}

/** Per-key sliding-window limiter (5 failures / 15 min, per summary.md). In-process only — acceptable for a single
  * backend instance; would need a shared store (e.g. the DB) to hold across multiple instances.
  */
final case class InMemoryRateLimiter(state: Ref[Map[String, Vector[Long]]]) extends RateLimiter {
  import InMemoryRateLimiter._

  private def prune(attempts: Vector[Long], now: Long): Vector[Long] = {
    attempts.filter(t => now - t <= windowMillis)
  }

  def isBlocked(key: String): UIO[Boolean] = {
    for {
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      attempts <- state.get.map(_.getOrElse(normalize(key), Vector.empty))
    } yield prune(attempts, now).size >= maxAttempts
  }

  def recordFailure(key: String): UIO[Unit] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _   <- state.update { m =>
               val k      = normalize(key)
               val pruned = prune(m.getOrElse(k, Vector.empty), now)
               m.updated(k, pruned :+ now)
             }
    } yield ()
  }

  def clear(key: String): UIO[Unit] = {
    state.update(_ - normalize(key))
  }

  /** Drops keys with no attempts left in the window, bounding map growth from one-off email addresses. Runs forever —
    * fork as a daemon fiber at startup.
    */
  def runPruner: URIO[Any, Nothing] = {
    (
      for {
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        _   <- state.update(_.view.mapValues(prune(_, now)).filter(_._2.nonEmpty).toMap)
        _   <- ZIO.sleep(pruneInterval)
      } yield ()
    ).forever
  }
}

object InMemoryRateLimiter {
  val maxAttempts             = 5
  val window: Duration        = 15.minutes
  val windowMillis: Long      = window.toMillis
  val pruneInterval: Duration = 15.minutes

  private def normalize(key: String): String = key.trim.toLowerCase
}
