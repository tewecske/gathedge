package webapp1.backend.service

import webapp1.backend.config.AppConfig
import webapp1.backend.db.{EmailVerificationTokenRepository, LoginAttemptRepository, SessionRepository}
import zio.*

import java.util.concurrent.TimeUnit

/** Deletes the three kinds of row that outlive their own usefulness: sessions, which are only ever marked expired or
  * revoked rather than removed; spent or expired email verification tokens; and sign-in attempts older than
  * `app.login-attempt-retention-days`. Without this all three tables grow for the lifetime of the deployment. Fork as a
  * daemon fiber once at startup, next to [[RateLimiter.runPruner]].
  *
  * '''`login_attempts` is the one an outsider controls.''' A row is written at every exit from `AuthService.login`,
  * including the one the rate limiter takes before a password is ever checked, so anyone who can reach the sign-in
  * endpoint can add rows as fast as they can send requests, without an account and without guessing anything. Retention
  * is what stops that being unbounded — and, since two of the system overview's counts are `COUNT(*)` over this table,
  * what stops it becoming slow as well.
  *
  * `audit_log` is deliberately '''not''' swept. It records what administrators did, its rows are written only by
  * administrator action, and how long to keep that is a decision for whoever operates the deployment rather than a
  * default this file should pick.
  *
  * The same sweep is available on demand through `SystemService.prune`, which is what the administrator's maintenance
  * button calls; [[sweep]] is the shared body so the two cannot drift.
  */
object SessionReaper {

  val interval: Duration = 1.hour

  val jobName: String = "session-reaper"

  /** What one pass removed: sessions, verification tokens, sign-in attempts. Named rather than a bare triple, because
    * three `Long`s in a row is exactly the shape that gets silently reordered.
    */
  final case class Swept(sessions: Long, verificationTokens: Long, loginAttempts: Long)

  /** One pass. Returns what it removed, so both the hourly loop and the on-demand button can report it. */
  def sweep: RIO[SessionRepository & EmailVerificationTokenRepository & LoginAttemptRepository & AppConfig, Swept] = {
    for {
      config   <- ZIO.service[AppConfig]
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      sessions <- SessionRepository.deleteExpired(now)
      tokens   <- EmailVerificationTokenRepository.deleteExpired(now)
      cutoff    = now - config.app.loginAttemptRetentionDays.toLong * 24L * 60L * 60L * 1000L
      attempts <- LoginAttemptRepository.deleteOlderThan(cutoff)
    } yield Swept(sessions, tokens, attempts)
  }

  def run: URIO[
    SessionRepository & EmailVerificationTokenRepository & LoginAttemptRepository & AppConfig & BackgroundJobs,
    Nothing,
  ] = {
    val once = {
      for {
        swept <- sweep
        _     <- ZIO.when(swept.sessions > 0)(ZIO.logInfo(s"Purged ${swept.sessions} expired session(s)"))
        _     <- ZIO.when(swept.verificationTokens > 0)(
                   ZIO.logInfo(s"Purged ${swept.verificationTokens} expired verification token(s)")
                 )
        _     <- ZIO.when(swept.loginAttempts > 0)(
                   ZIO.logInfo(s"Purged ${swept.loginAttempts} expired sign-in attempt record(s)")
                 )
        _     <- BackgroundJobs.recordSuccess(
                   jobName,
                   s"removed ${swept.sessions} session(s), ${swept.verificationTokens} verification token(s) and " +
                     s"${swept.loginAttempts} sign-in attempt record(s)",
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
