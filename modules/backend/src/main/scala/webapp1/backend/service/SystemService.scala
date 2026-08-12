package webapp1.backend.service

import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  LoginAttemptRepository,
  MetricsRepository,
  MigrationRow,
  SessionRepository,
}
import webapp1.backend.security.SessionAuth
import webapp1.shared.dto.{
  AuditAction,
  ConfigSummary,
  DbStats,
  LoginOutcome,
  MigrationInfo,
  PruneResult,
  RuntimeInfo,
  SystemOverview,
}
import zio.*

import java.util.concurrent.TimeUnit

/** The deployment's account of itself, for the administrator's system overview.
  *
  * This is the one part of the admin surface that is not about accounts at all. It exists because every operational
  * fact the application knows — which providers are configured, whether mail is really being sent, which schema version
  * is applied, whether the reaper is still running, how much the database is holding — was previously reachable only by
  * reading a log file or the container's environment.
  *
  * '''No configured secret is reported.''' Every credential-bearing setting is reduced to a boolean or dropped
  * outright, and the database URL has any userinfo stripped, so the response is safe to look at over a shoulder and
  * safe to paste into a ticket. [[SystemServiceSpec]] asserts this on the serialised bytes rather than trusting the
  * reading of it.
  */
trait SystemService {
  def overview: UIO[SystemOverview]

  /** Runs the sweep [[SessionReaper]] performs hourly, now, and clears nothing else. */
  def prune(actor: AdminActor): UIO[PruneResult]
}

object SystemService {
  def overview: URIO[SystemService, SystemOverview] =
    ZIO.serviceWithZIO[SystemService](_.overview)

  def prune(actor: AdminActor): URIO[SystemService, PruneResult] =
    ZIO.serviceWithZIO[SystemService](_.prune(actor))

  /** The window the "failed sign-ins" statistic covers. A day is long enough to catch a burst overnight and short
    * enough that a quiet number means the deployment is currently quiet.
    */
  val failedLoginWindow: Duration = 24.hours

  /** How long [[SystemServiceLive.dbStats]] is memoised for.
    *
    * One overview costs about fifteen `COUNT(*)` queries, two of them over `login_attempts` and `audit_log` — the
    * fastest-growing tables in the schema, and in Postgres a sequential scan each. A page that polls, or two
    * administrators with it open, multiplied that by however many of them there were.
    *
    * Half a minute is short enough that nobody watching the screen would call it stale and long enough to collapse a
    * refresh loop into one pass. It deliberately does not cover the configuration or the runtime figures: the heap
    * reading is the one genuinely live value on that page, and a memoised one would be worse than none.
    */
  val statsCacheTtl: Duration = 30.seconds

  /** Captures the process start instant as the layer is built, which for this layer is startup. */
  val live: URLayer[
    AppConfig & MetricsRepository & SessionRepository & EmailVerificationTokenRepository & LoginAttemptRepository &
      AuditLogRepository & AuditTrail & RateLimiter & BackgroundJobs,
    SystemService,
  ] = ZLayer {
    for {
      config      <- ZIO.service[AppConfig]
      metrics     <- ZIO.service[MetricsRepository]
      sessionRepo <- ZIO.service[SessionRepository]
      tokenRepo   <- ZIO.service[EmailVerificationTokenRepository]
      attemptRepo <- ZIO.service[LoginAttemptRepository]
      auditRepo   <- ZIO.service[AuditLogRepository]
      auditTrail  <- ZIO.service[AuditTrail]
      rateLimiter <- ZIO.service[RateLimiter]
      jobs        <- ZIO.service[BackgroundJobs]
      startedAt   <- Clock.currentTime(TimeUnit.MILLISECONDS)
      statsCache  <- Ref.make(Option.empty[(Long, DbStats)])
    } yield SystemServiceLive(
      config,
      metrics,
      sessionRepo,
      tokenRepo,
      attemptRepo,
      auditRepo,
      auditTrail,
      rateLimiter,
      jobs,
      startedAt,
      statsCache,
    ): SystemService
  }

  /** Strips any `user:password@` from a JDBC URL before it is reported.
    *
    * The configured URL is normally credential-free (`db.user`/`db.password` are separate settings), but a deployment
    * that sets `DB_URL` to a full connection string would otherwise put its database password on an admin screen.
    */
  def redactUrl(url: String): String = {
    url.replaceAll("//[^/@]*@", "//")
  }
}

final case class SystemServiceLive(
  config: AppConfig,
  metrics: MetricsRepository,
  sessionRepo: SessionRepository,
  tokenRepo: EmailVerificationTokenRepository,
  attemptRepo: LoginAttemptRepository,
  auditRepo: AuditLogRepository,
  auditTrail: AuditTrail,
  rateLimiter: RateLimiter,
  jobs: BackgroundJobs,
  startedAt: Long,
  /** The last computed [[DbStats]] and the instant it was computed, or `None` when nothing is memoised — see
    * [[SystemService.statsCacheTtl]].
    */
  statsCache: Ref[Option[(Long, DbStats)]],
) extends SystemService {

  private def configSummary: ConfigSummary = {
    ConfigSummary(
      env = config.app.env,
      production = config.isProduction,
      publicBaseUrl = config.app.publicBaseUrl,
      serverHost = config.app.serverHost,
      serverPort = config.app.serverPort,
      requireEmailVerification = config.app.requireEmailVerification,
      sessionCookieSecure = config.session.cookieSecure,
      configuredOAuthProviders = config.configuredOAuthProviders,
      // The host is an address, not a credential, and knowing which relay is in use is most of the value here. The
      // SMTP username and password are simply absent.
      mailConfigured = config.isMailConfigured,
      mailFrom = config.mail.from,
      mailSmtpHost = Option(config.mail.smtp.host).filter(_.nonEmpty),
      mailSmtpPort = config.mail.smtp.port,
      mailStartTls = config.mail.smtp.startTls,
      databaseUrl = SystemService.redactUrl(config.db.url),
      databaseUser = config.db.user,
      sessionValidityHours = SessionAuth.sessionDuration.toHours,
      verificationValidityHours = AuthService.verificationValidity.toHours,
      rateLimitMaxAttempts = RateLimiter.maxAttempts,
      rateLimitWindowMinutes = RateLimiter.window.toMinutes,
      nettyMaxThreads = config.netty.maxThreads,
      // Empty outside production, and the reason the process refused to start if it is not.
      productionIssues = config.productionIssues,
    )
  }

  private def toMigrationInfo(row: MigrationRow): MigrationInfo = {
    MigrationInfo(row.installedRank, row.version, row.description, row.success)
  }

  private def runtimeInfo: UIO[RuntimeInfo] = {
    for {
      now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
      // A failed read here must not take the whole page down: the counts and the configuration are still worth
      // showing, and an empty migration list is itself a visible symptom.
      migrations <- metrics.migrations.orElseSucceed(Nil)
      // Read inside the effect rather than at construction: these are the only genuinely time-varying values on the
      // page, and a memoised heap figure would be worse than none.
      snapshot   <- ZIO.succeed {
                      val runtime = java.lang.Runtime.getRuntime
                      (runtime.totalMemory - runtime.freeMemory, runtime.maxMemory, runtime.availableProcessors)
                    }
      threads    <- ZIO.succeed(Thread.activeCount())
    } yield {
      val (heapUsed, heapMax, processors) = snapshot
      RuntimeInfo(
        apiVersion = AppConfig.apiVersion,
        startedAt = startedAt,
        uptimeMillis = now - startedAt,
        // `java.lang.System`: `zio.System` is what the wildcard import puts in scope here, and it has no
        // `getProperty` — a property read is not something this needs to go through ZIO's effectful wrapper for.
        jvmVersion = java.lang.System.getProperty("java.version", "unknown"),
        availableProcessors = processors,
        heapUsedBytes = heapUsed,
        heapMaxBytes = heapMax,
        liveThreads = threads,
        migrations = migrations.map(toMigrationInfo),
      )
    }
  }

  /** [[countStats]] behind [[SystemService.statsCacheTtl]].
    *
    * Read-and-recompute rather than a `Ref.modify` holding a lock: two overviews landing together may both miss and
    * both count, which costs one extra pass and keeps the counting out of the critical section.
    */
  private def dbStats: UIO[DbStats] = {
    for {
      now    <- Clock.currentTime(TimeUnit.MILLISECONDS)
      cached <- statsCache.get
      stats  <-
        cached match {
          case Some((computedAt, stats)) if now - computedAt < SystemService.statsCacheTtl.toMillis =>
            ZIO.succeed(stats)
          case _                                                                                    =>
            countStats.tap(fresh => statsCache.set(Some((now, fresh))))
        }
    } yield stats
  }

  private def countStats: UIO[DbStats] = {
    (
      for {
        now            <- Clock.currentTime(TimeUnit.MILLISECONDS)
        counts         <- metrics.counts
        sessions       <- sessionRepo.countAll
        activeSessions <- sessionRepo.countActive(now)
        tokens         <- tokenRepo.countAll
        expiredTokens  <- tokenRepo.countExpired(now)
        attempts       <- attemptRepo.countAll
        recentAttempts <- attemptRepo.countSince(now - SystemService.failedLoginWindow.toMillis, None)
        recentSuccess  <- attemptRepo.countSince(
                            now - SystemService.failedLoginWindow.toMillis,
                            Some(LoginOutcome.success),
                          )
        auditEntries   <- auditRepo.countAll
        blockedKeys    <- rateLimiter.snapshot.map(_.count(_.blocked))
      } yield DbStats(
        users = counts.users,
        admins = counts.admins,
        unverifiedUsers = counts.unverifiedUsers,
        usersWithoutPassword = counts.usersWithoutPassword,
        oauthIdentities = counts.oauthIdentities,
        sessions = sessions,
        activeSessions = activeSessions,
        verificationTokens = tokens,
        expiredVerificationTokens = expiredTokens,
        loginAttempts = attempts,
        // Counted as "everything that was not a success", so a failure outcome added by a later build is included
        // without this having to be told about it.
        failedLoginsLast24h = recentAttempts - recentSuccess,
        auditEntries = auditEntries,
        blockedRateLimitKeys = blockedKeys.toLong,
      )
    ).orDie
  }

  def overview: UIO[SystemOverview] = {
    for {
      runtime <- runtimeInfo
      stats   <- dbStats
      running <- jobs.statuses
    } yield SystemOverview(configSummary, runtime, stats, running)
  }

  def prune(actor: AdminActor): UIO[PruneResult] = {
    for {
      // The reaper's own body, not a copy of it, so the button and the hourly loop cannot remove different things.
      swept <- SessionReaper.sweep.provideEnvironment(ZEnvironment(sessionRepo, tokenRepo, attemptRepo, config)).orDie
      // Stale keys only: this is housekeeping, and it must not quietly unblock an account that is being brute-forced
      // right now. Unblocking is `clearRateLimits`, which is a separate, audited action.
      keys  <- rateLimiter.pruneStale
      // This is the one operation that changes the counts from inside this service, so it is also the one that has to
      // drop the memo — an administrator who prunes and sees the same numbers would reasonably conclude it did not work.
      _     <- statsCache.set(None)
      result = PruneResult(swept.sessions, swept.verificationTokens, swept.loginAttempts, keys)
      _     <- auditTrail.recordSystem(
                 actor,
                 AuditAction.systemPrune,
                 s"pruned ${swept.sessions} session(s), ${swept.verificationTokens} verification token(s), " +
                   s"${swept.loginAttempts} sign-in attempt record(s) and $keys rate-limit key(s)",
               )
    } yield result
  }
}
