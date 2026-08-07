package webapp1.backend.service

import webapp1.backend.db.{EmailVerificationTokenRepository, SessionRepository}
import zio.*

import java.util.concurrent.TimeUnit

/** Deletes the two kinds of row that outlive their own usefulness: sessions, which are only ever marked expired or
  * revoked rather than removed, and spent or expired email verification tokens. Without this both tables grow for the
  * lifetime of the deployment. Fork as a daemon fiber once at startup, next to [[RateLimiter.runPruner]].
  *
  * The same sweep is available on demand through `SystemService.prune`, which is what the administrator's maintenance
  * button calls; [[sweep]] is the shared body so the two cannot drift.
  */
object SessionReaper {

  val interval: Duration = 1.hour

  val jobName: String = "session-reaper"

  /** One pass. Returns what it removed, so both the hourly loop and the on-demand button can report it. */
  def sweep: RIO[SessionRepository & EmailVerificationTokenRepository, (Long, Long)] = {
    for {
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      sessions <- SessionRepository.deleteExpired(now)
      tokens   <- EmailVerificationTokenRepository.deleteExpired(now)
    } yield (sessions, tokens)
  }

  def run: URIO[SessionRepository & EmailVerificationTokenRepository & BackgroundJobs, Nothing] = {
    val once = {
      for {
        result            <- sweep
        (sessions, tokens) = result
        _                 <- ZIO.when(sessions > 0)(ZIO.logInfo(s"Purged $sessions expired session(s)"))
        _                 <- ZIO.when(tokens > 0)(ZIO.logInfo(s"Purged $tokens expired verification token(s)"))
        _                 <- BackgroundJobs.recordSuccess(
                               jobName,
                               s"removed $sessions session(s) and $tokens verification token(s)",
                             )
      } yield ()
    }
    // A failed sweep must not kill the fiber: log it, report it, and try again next interval.
    val loop = {
      once.catchAllCause { cause =>
        ZIO.logErrorCause("Could not purge expired rows", cause) *>
          BackgroundJobs.recordFailure(jobName, cause.squash.getMessage)
      } *> ZIO.sleep(interval)
    }
    BackgroundJobs.register(jobName, interval) *> loop.forever
  }
}
