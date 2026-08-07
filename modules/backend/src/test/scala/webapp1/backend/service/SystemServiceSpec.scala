package webapp1.backend.service

import webapp1.backend.{RecordingEmailSender, TestDataSource}
import webapp1.backend.config.AppConfig
import webapp1.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  LoginAttemptRepository,
  MetricsRepository,
  OAuthIdentityRepository,
  SessionRepository,
  UserRepository,
}
import webapp1.backend.security.PasswordHasher
import webapp1.shared.dto.AuditAction
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
        EmailVerificationTokenRepository.test ++ LoginAttemptRepository.test ++ AuditLogRepository.test ++
        MetricsRepository.test
    )
  }

  private val layer = {
    (
      repoLayers ++ PasswordHasher.live ++ RateLimiter.live ++ BackgroundJobs.live ++ AppConfig.live ++
        RecordingEmailSender.live >+> (AuthService.live ++ AuditTrail.live)
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
        // `db.password` is deliberately not asserted by value — the development default is "webapp1", which is also
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
        SystemService.redactUrl("jdbc:postgresql://webapp1:hunter2@db:5432/webapp1") ==
          "jdbc:postgresql://db:5432/webapp1",
        // The configured shape has no userinfo and must pass through untouched.
        SystemService.redactUrl(
          "jdbc:postgresql://localhost:5432/webapp1"
        ) == "jdbc:postgresql://localhost:5432/webapp1",
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
    test("reports the applied schema, which is at least the migration that added these tables") {
      for {
        overview <- SystemService.overview
      } yield assertTrue(
        overview.runtime.migrations.nonEmpty,
        overview.runtime.migrations.forall(_.success),
        overview.runtime.migrations.flatMap(_.version).contains("7"),
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
        audited <- AdminService.auditLog(50, None, Some(AuditAction.systemPrune), None, None)
      } yield assertTrue(
        before.stats.sessions == 1L,
        result.sessions == 1L,
        after.stats.sessions == 0L,
        audited.size == 1,
        audited.head.actorUserId.contains(user.id),
      )
    },
  ).provide(layer)
}
