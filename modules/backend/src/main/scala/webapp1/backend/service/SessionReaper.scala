package webapp1.backend.service

import webapp1.backend.db.SessionRepository
import zio.*

import java.util.concurrent.TimeUnit

/** Deletes session rows that can no longer authenticate anyone. Sessions are only ever marked expired or revoked, never
  * removed, so without this the table grows for the lifetime of the deployment. Fork as a daemon fiber once at startup,
  * next to [[RateLimiter.runPruner]].
  */
object SessionReaper {

  val interval: Duration = 1.hour

  def run: URIO[SessionRepository, Nothing] = {
    val once = {
      for {
        repo <- ZIO.service[SessionRepository]
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        deleted <- repo.deleteExpired(now)
        _ <- ZIO.when(deleted > 0)(ZIO.logInfo(s"Purged $deleted expired session(s)"))
      } yield ()
    }
    // A failed sweep must not kill the fiber: log it and try again next interval.
    (once.catchAllCause(cause => ZIO.logErrorCause("Could not purge expired sessions", cause)) *> ZIO.sleep(interval))
      .forever
  }
}
