package webapp1.backend.service

import webapp1.backend.db.{EmailVerificationTokenRepository, SessionRepository}
import zio.*

import java.util.concurrent.TimeUnit

/** Deletes the two kinds of row that outlive their own usefulness: sessions, which are only ever marked expired or
  * revoked rather than removed, and spent or expired email verification tokens. Without this both tables grow for the
  * lifetime of the deployment. Fork as a daemon fiber once at startup, next to [[RateLimiter.runPruner]].
  */
object SessionReaper {

  val interval: Duration = 1.hour

  def run: URIO[SessionRepository & EmailVerificationTokenRepository, Nothing] = {
    val once = {
      for {
        now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
        sessions <- SessionRepository.deleteExpired(now)
        _        <- ZIO.when(sessions > 0)(ZIO.logInfo(s"Purged $sessions expired session(s)"))
        tokens   <- EmailVerificationTokenRepository.deleteExpired(now)
        _        <- ZIO.when(tokens > 0)(ZIO.logInfo(s"Purged $tokens expired verification token(s)"))
      } yield ()
    }
    // A failed sweep must not kill the fiber: log it and try again next interval.
    (once.catchAllCause(cause => ZIO.logErrorCause("Could not purge expired rows", cause)) *> ZIO.sleep(
      interval
    )).forever
  }
}
