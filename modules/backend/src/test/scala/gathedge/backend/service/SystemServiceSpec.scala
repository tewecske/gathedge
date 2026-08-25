package gathedge.backend.service

import gathedge.backend.{RecordingEmailSender, TestCaptchaService, TestDataSource}
import gathedge.backend.config.AppConfig
import gathedge.backend.i18n.Messages
import gathedge.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  GameRepository,
  GroupRepository,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  MetricsRepository,
  OAuthIdentityRepository,
  PasswordResetTokenRepository,
  SessionRepository,
  UsageEventRepository,
  UserRepository,
  WordRepository,
}
import gathedge.backend.security.PasswordHasher
import gathedge.shared.dto.{AuditAction, Paging}
import zio._
import zio.json._
import zio.test._

/** The system overview. The assertion that matters most here is the negative one: no configured secret may reach the
  * wire, and it is checked against the serialised bytes rather than by reading the mapping.
  */
object SystemServiceSpec extends ZIOSpecDefault {

  private val repoLayers = {
    TestDataSource.sqlite >>> (
      UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
        EmailVerificationTokenRepository.test ++ PasswordResetTokenRepository.test ++ LoginAttemptRepository.test ++
        GuestClaimCodeRepository.test ++ AuditLogRepository.test ++ UsageEventRepository.test ++
        MetricsRepository.test ++ GameRepository.test ++ WordRepository.test ++ GroupRepository.test
    )
  }

  private val layer = {
    (
      repoLayers ++ PasswordHasher.live ++ RateLimiter.live ++ BackgroundJobs.live ++ AppConfig.live ++
        RecordingEmailSender.live ++ Messages.live ++ TestCaptchaService.live ++ GameWordList.live >+>
        (AuthService.live ++ AuditTrail.live ++ GameService.live)
    ) >+> (AdminService.live ++ SystemService.live)
  }

  def spec = suite("SystemService (SQLite)")(
    test("reports no configured secret") {
      for {
        overview <- SystemService.overview
        config   <- ZIO.serviceWith[AppConfig](identity)
      } yield {
        val rendered = overview.config.toJson
        // Field names as well as values: a new field carrying a credential would have to be called something, and
        // every name a credential could plausibly be given is caught here even if its configured value is empty.
        //
        // `db.password` is deliberately not asserted by value — the development default is "gathedge", which is also
        // the database name and the database user, so the assertion would fail on the URL rather than on a leak.
        assertTrue(
          !rendered.contains(config.bootstrapAdmin.password),
          !rendered.toLowerCase.contains("password"),
          !rendered.toLowerCase.contains("secret"),
          !rendered.toLowerCase.contains("clientid"),
          !rendered.toLowerCase.contains("username"),
        )
      }
    },
    test("strips credentials out of a database URL that carries them") {
      assertTrue(
        SystemService.redactUrl("jdbc:postgresql://gathedge:hunter2@db:5432/gathedge") ==
          "jdbc:postgresql://db:5432/gathedge",
        // The configured shape has no userinfo and must pass through untouched.
        SystemService.redactUrl(
          "jdbc:postgresql://localhost:5432/gathedge"
        ) == "jdbc:postgresql://localhost:5432/gathedge",
      )
    },
    test("counts what the deployment is holding") {
      for {
        _        <- AdminService.createUser(AdminActor.system, "counted@example.com", "password123", isAdmin = true)
        signedUp <- AuthService.signup("unconfirmed-count@example.com", "password123")
        _        <- AuthService.login("counted@example.com", "password123")
        _        <- AuthService.login("counted@example.com", "wrong").either
        overview <- SystemService.overview
      } yield assertTrue(
        overview.stats.users == 2L,
        overview.stats.admins == 1L,
        overview.stats.unverifiedUsers == 1L,
        overview.stats.activeSessions == 2L,
        overview.stats.verificationTokens == 1L,
        overview.stats.loginAttempts == 2L,
        overview.stats.failedLoginsLast24h == 1L,
        overview.stats.auditEntries == 1L,
        signedUp._1.id > 0,
      )
    },
    test("reports the applied schema, which is at least the baseline that created these tables") {
      for {
        overview <- SystemService.overview
      } yield assertTrue(
        overview.runtime.migrations.nonEmpty,
        overview.runtime.migrations.forall(_.success),
        overview.runtime.migrations.flatMap(_.version).contains("1"),
        overview.runtime.apiVersion == AppConfig.apiVersion,
        overview.runtime.uptimeMillis >= 0L,
      )
    },
    test("a job that has registered but not run is reported as pending") {
      for {
        _        <- BackgroundJobs.register("test-job", 5.minutes)
        overview <- SystemService.overview
      } yield assertTrue(
        overview.jobs.exists(job => job.name == "test-job" && job.lastRunAt.isEmpty && job.intervalMinutes == 5L)
      )
    },
    test("pruning removes expired sessions and links, and audits what it removed") {
      for {
        user    <- AdminService.createUser(AdminActor.system, "pruned@example.com", "password123", isAdmin = false)
        session <- AuthService.login("pruned@example.com", "password123").map(_._2)
        // Revoked *and* aged past the reaper's cutoff, which is `now`: a session revoked a moment ago is exactly what
        // the sweep is meant to collect.
        _       <- AuthService.logout(session)
        _       <- TestClock.adjust(1.second)
        before  <- SystemService.overview
        result  <- SystemService.prune(AdminActor(user.id))
        after   <- SystemService.overview
        audited <- AdminService.auditLog(Paging.firstPage, 50, None, false, Some(AuditAction.systemPrune), None, None)
      } yield assertTrue(
        before.stats.sessions == 1L,
        result.sessions == 1L,
        after.stats.sessions == 0L,
        audited.items.size == 1,
        audited.items.head.actorUserId.contains(user.id),
      )
    },
    // `login_attempts` is the one table an unauthenticated caller can grow at will — a row is written at every exit
    // from `login`, including the one the rate limiter takes before checking anything. Nothing swept it, and
    // `deleteOlderThan` sat unused.
    test("the sweep drops sign-in attempts past the retention window and keeps the ones inside it") {
      for {
        _      <- AdminService.createUser(AdminActor.system, "aged@example.com", "password123", isAdmin = false)
        _      <- AuthService.login("aged@example.com", "nope12345").either
        _      <- TestClock.adjust(31.days)
        _      <- AuthService.login("aged@example.com", "nope12345").either
        before <- SystemService.overview
        result <- SystemService.prune(AdminActor.system)
        after  <- SystemService.overview
      } yield assertTrue(
        before.stats.loginAttempts == 2L,
        result.loginAttempts == 1L,
        after.stats.loginAttempts == 1L,
      )
    },
    // One overview is about fifteen COUNT(*) queries, two of them over the tables that grow fastest. The memo is what
    // stops a polling page paying for that every time; dropping it on prune is what stops the button looking broken.
    test("the counts are memoised for the cache window, and pruning drops the memo") {
      for {
        _      <- AdminService.createUser(AdminActor.system, "counted-once@example.com", "password123", isAdmin = false)
        first  <- SystemService.overview
        _      <- AdminService.createUser(AdminActor.system, "counted-later@example.com", "password123", isAdmin = false)
        // Still inside the window, so this is the memo rather than a fresh count.
        second <- SystemService.overview
        _      <- TestClock.adjust(SystemService.statsCacheTtl)
        third  <- SystemService.overview
      } yield assertTrue(first.stats.users == 1L, second.stats.users == 1L, third.stats.users == 2L)
    },
    test("pruning drops the memo even when it removed nothing") {
      for {
        first  <- SystemService.overview
        _      <- AdminService.createUser(AdminActor.system, "after-prune@example.com", "password123", isAdmin = false)
        result <- SystemService.prune(AdminActor.system)
        after  <- SystemService.overview
      } yield assertTrue(first.stats.users == 0L, result.sessions == 0L, after.stats.users == 1L)
    },
  ).provide(layer)
}
