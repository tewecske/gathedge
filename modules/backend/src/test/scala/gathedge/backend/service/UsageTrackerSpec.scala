package gathedge.backend.service

import gathedge.backend.{RecordingEmailSender, TestCaptchaService, TestDataSource}
import gathedge.backend.config.AppConfig
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
  UsageEventRow,
  UserRepository,
  WordRepository,
}
import gathedge.backend.i18n.Messages
import gathedge.backend.security.{PasswordHasher, SessionAuth}
import gathedge.backend.telemetry.Telemetry
import zio.*
import zio.http.*
import zio.test.*

/** The usage-event write is off the request path: `record` only enqueues, and one background fiber drains the queue,
  * resolves the account behind the session cookie, and writes the row. So every assertion here waits for the row to
  * appear rather than expecting it the instant `record` returns — that wait is the point.
  */
object UsageTrackerSpec extends ZIOSpecDefault {

  /** Fails the insert whenever the row's status is 599 — the suite's sentinel for "make the write throw" — and
    * delegates everything else. Used to prove the drain fiber swallows a failed write and keeps going.
    */
  final case class FlakyUsageEventRepository(delegate: UsageEventRepository) extends UsageEventRepository {
    def insert(row: UsageEventRow): Task[UsageEventRow]                = {
      if (row.status == 599) ZIO.fail(new RuntimeException("boom")) else delegate.insert(row)
    }
    def countsByRoute(since: Long): Task[List[(String, String, Long)]] = delegate.countsByRoute(since)
    def countsByUser(since: Long): Task[List[(Long, Long)]]            = delegate.countsByUser(since)
    def distinctIpCountsByUser(since: Long): Task[List[(Long, Int)]]   = delegate.distinctIpCountsByUser(since)
    def countAll: Task[Long]                                           = delegate.countAll
    def deleteOlderThan(before: Long): Task[Long]                      = delegate.deleteOlderThan(before)
  }

  private val repoLayers = {
    TestDataSource.sqlite >>> (
      UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
        EmailVerificationTokenRepository.test ++ PasswordResetTokenRepository.test ++ LoginAttemptRepository.test ++
        GuestClaimCodeRepository.test ++ AuditLogRepository.test ++ GameRepository.test ++ WordRepository.test ++
        GroupRepository.test ++ MetricsRepository.test ++
        (UsageEventRepository.test >>> ZLayer.fromFunction((d: UsageEventRepository) =>
          FlakyUsageEventRepository(d): UsageEventRepository
        ))
    )
  }

  private val layer = {
    (
      repoLayers ++ PasswordHasher.live ++ RateLimiter.live ++ BackgroundJobs.live ++ AppConfig.live ++
        RecordingEmailSender.live ++ Messages.live ++ TestCaptchaService.live ++ GameWordList.live ++ Telemetry.live >+>
        (AuthService.live ++ AuditTrail.live ++ GameService.live)
    ) >+> (AdminService.live ++ UsageTracker.live)
  }

  /** Poll until the predicate holds. A real delay between reads, so the drain fiber gets the CPU; bounded by the
    * suite's `timeout` aspect.
    */
  private def eventually(p: Long => Boolean): ZIO[UsageEventRepository, Throwable, Long] = {
    UsageEventRepository.countAll.delay(10.millis).repeatUntil(p)
  }

  private def get(path: String, sessionId: Option[String]): Request = {
    val base = Request.get(path)
    sessionId.fold(base)(sid => base.addCookie(Cookie.Request(SessionAuth.cookieName, sid)))
  }

  def spec = suite("UsageTracker (SQLite)")(
    test("record enqueues and the drain fiber writes the row") {
      for {
        before <- UsageEventRepository.countAll
        _      <- UsageTracker.record(get("/api/words", None), Status.Ok)
        after  <- eventually(_ == before + 1L)
      } yield assertTrue(after == before + 1L)
    },
    test("the drain fiber resolves the account behind the session cookie") {
      for {
        created <- AdminService.createUser(AdminActor.system, "tracked@example.com", "password123", isAdmin = false)
        session <- AuthService.login("tracked@example.com", "password123").map(_._2)
        before  <- UsageEventRepository.countAll
        _       <- UsageTracker.record(get("/api/words", Some(session)), Status.Ok)
        _       <- eventually(_ == before + 1L)
        byUser  <- UsageEventRepository.countsByUser(0L)
      } yield assertTrue(byUser.exists { case (userId, hits) => userId == created.id && hits >= 1L })
    },
    test("a request with no session cookie is recorded with no account") {
      for {
        attributedBefore <- UsageEventRepository.countsByUser(0L).map(_.map(_._2).sum)
        before           <- UsageEventRepository.countAll
        _                <- UsageTracker.record(get("/api/words", None), Status.Ok)
        _                <- eventually(_ == before + 1L)
        attributedAfter  <- UsageEventRepository.countsByUser(0L).map(_.map(_._2).sum)
      } yield assertTrue(attributedAfter == attributedBefore)
    },
    test("a failed write is swallowed and the drain fiber keeps going") {
      for {
        before <- UsageEventRepository.countAll
        _      <- UsageTracker.record(get("/api/boom", None), Status.Custom(599))
        _      <- UsageTracker.record(get("/api/words", None), Status.Ok)
        after  <- eventually(_ == before + 1L)
      } yield assertTrue(after == before + 1L)
    },
  ).provideShared(layer) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds) @@ TestAspect.sequential
}
