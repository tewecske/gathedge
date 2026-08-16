package gathedge.backend.service

import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  EmailVerificationTokenRepository,
  LoginAttemptRepository,
  PasswordResetTokenRepository,
  SessionRepository,
  UsageEventRepository,
  UserRepository,
}
import zio.*

import java.util.concurrent.TimeUnit

/** Deletes the kinds of row that outlive their own usefulness: sessions, which are only ever marked expired or revoked
  * rather than removed; spent or expired email verification tokens; spent or expired password-reset tokens; and sign-in
  * attempts older than `app.login-attempt-retention-days`. Without this every one of those tables grows for the
  * lifetime of the deployment. Fork as a daemon fiber once at startup, next to [[RateLimiter.runPruner]].
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

  /** How many abandoned guest accounts one pass will remove. Bounded so a deployment that has accumulated a great many
    * of them clears them over several passes rather than in one long transaction.
    */
  private val guestSweepLimit = 500

  /** What one pass removed. Named rather than a bare tuple, because several `Long`s in a row is exactly the shape that
    * gets silently reordered.
    */
  final case class Swept(
    sessions: Long,
    verificationTokens: Long,
    passwordResetTokens: Long,
    loginAttempts: Long,
    usageEvents: Long,
    guests: Long,
  )

  /** One pass. Returns what it removed, so both the hourly loop and the on-demand button can report it.
    *
    * '''The guest sweep only ever takes empty accounts.''' A guest holding tags is never removed, however long its
    * session has been gone: it has no address to recover it by, but somebody may well have its transfer code written
    * down. What this clears is the account a visitor minted and then walked away from before tagging anything.
    */
  def sweep: RIO[
    SessionRepository & EmailVerificationTokenRepository & PasswordResetTokenRepository & LoginAttemptRepository &
      UsageEventRepository & UserRepository & AppConfig,
    Swept,
  ] = {
    for {
      config      <- ZIO.service[AppConfig]
      now         <- Clock.currentTime(TimeUnit.MILLISECONDS)
      sessions    <- SessionRepository.deleteExpired(now)
      tokens      <- EmailVerificationTokenRepository.deleteExpired(now)
      resetTokens <- PasswordResetTokenRepository.deleteExpired(now)
      cutoff       = now - config.app.loginAttemptRetentionDays.toLong * 24L * 60L * 60L * 1000L
      attempts    <- LoginAttemptRepository.deleteOlderThan(cutoff)
      usageCutoff  = now - config.app.usageEventRetentionDays.toLong * 24L * 60L * 60L * 1000L
      usageEvents <- UsageEventRepository.deleteOlderThan(usageCutoff)
      guestCutoff  = now - config.app.guestRetentionDays.toLong * 24L * 60L * 60L * 1000L
      abandoned   <- UserRepository.findAbandonedGuests(guestCutoff, guestSweepLimit)
      guests      <- ZIO.foreach(abandoned)(UserRepository.deleteById).map(_.sum)
    } yield Swept(sessions, tokens, resetTokens, attempts, usageEvents, guests)
  }

  def run: URIO[
    SessionRepository & EmailVerificationTokenRepository & PasswordResetTokenRepository & LoginAttemptRepository &
      UsageEventRepository & UserRepository & AppConfig & BackgroundJobs,
    Nothing,
  ] = {
    val once = {
      for {
        swept <- sweep
        _     <- ZIO.when(swept.sessions > 0)(ZIO.logInfo(s"Purged ${swept.sessions} expired session(s)"))
        _     <- ZIO.when(swept.verificationTokens > 0)(
                   ZIO.logInfo(s"Purged ${swept.verificationTokens} expired verification token(s)")
                 )
        _     <- ZIO.when(swept.passwordResetTokens > 0)(
                   ZIO.logInfo(s"Purged ${swept.passwordResetTokens} expired password reset token(s)")
                 )
        _     <- ZIO.when(swept.loginAttempts > 0)(
                   ZIO.logInfo(s"Purged ${swept.loginAttempts} expired sign-in attempt record(s)")
                 )
        _     <- ZIO.when(swept.usageEvents > 0)(ZIO.logInfo(s"Purged ${swept.usageEvents} expired usage event(s)"))
        _     <- ZIO.when(swept.guests > 0)(ZIO.logInfo(s"Purged ${swept.guests} abandoned guest account(s)"))
        _     <- BackgroundJobs.recordSuccess(
                   jobName,
                   s"removed ${swept.sessions} session(s), ${swept.verificationTokens} verification token(s), " +
                     s"${swept.passwordResetTokens} password reset token(s), " +
                     s"${swept.loginAttempts} sign-in attempt record(s), ${swept.usageEvents} usage event(s) and " +
                     s"${swept.guests} abandoned guest(s)",
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
