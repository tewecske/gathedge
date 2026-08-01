package webapp1.backend.service

import zio.*

import java.util.concurrent.TimeUnit

trait RateLimiter {
  def isBlocked(key: String): UIO[Boolean]
  def recordFailure(key: String): UIO[Unit]

  /** Background cleanup loop; fork as a daemon fiber once at startup. */
  def runPruner: URIO[Any, Nothing]
}

/** Per-key sliding-window limiter (5 failures / 15 min, per summary.md). In-process only — acceptable for a single
  * backend instance; would need a shared store (e.g. the DB) to hold across multiple instances.
  */
final class InMemoryRateLimiter(state: Ref[Map[String, Vector[Long]]]) extends RateLimiter {
  import InMemoryRateLimiter._

  private def prune(attempts: Vector[Long], now: Long): Vector[Long] = {
    attempts.filter(t => now - t <= windowMillis)
  }

  def isBlocked(key: String): UIO[Boolean] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      attempts <- state.get.map(_.getOrElse(normalize(key), Vector.empty))
    } yield prune(attempts, now).size >= maxAttempts
  }

  def recordFailure(key: String): UIO[Unit] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ <- state.update { m =>
        val k = normalize(key)
        val pruned = prune(m.getOrElse(k, Vector.empty), now)
        m.updated(k, pruned :+ now)
      }
    } yield ()
  }

  /** Drops keys with no attempts left in the window, bounding map growth from one-off email addresses. Runs forever —
    * fork as a daemon fiber at startup.
    */
  def runPruner: URIO[Any, Nothing] = {
    (
      for {
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        _ <- state.update(_.view.mapValues(prune(_, now)).filter(_._2.nonEmpty).toMap)
        _ <- ZIO.sleep(pruneInterval)
      } yield ()
    ).forever
  }
}

object InMemoryRateLimiter {
  val maxAttempts = 5
  val window: Duration = 15.minutes
  val windowMillis: Long = window.toMillis
  val pruneInterval: Duration = 15.minutes

  private def normalize(email: String): String = email.trim.toLowerCase

  val live: ULayer[RateLimiter] = ZLayer.fromZIO(
    Ref.make(Map.empty[String, Vector[Long]]).map(new InMemoryRateLimiter(_))
  )
}
