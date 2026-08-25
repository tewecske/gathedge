package gathedge.backend.http

import gathedge.backend.{TestAuthLayers, TestCaptchaService, TestDataSource}
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
  UserRepository,
  WordRepository,
}
import gathedge.backend.security.PasswordHasher
import gathedge.backend.service.{
  AdminActor,
  AdminService,
  AuditTrail,
  AuthService,
  BackgroundJobs,
  GameService,
  GameWordList,
  OAuthClients,
  RateLimiter,
  SystemService,
  UsageStatsService,
  WordService,
}
import zio.*
import zio.http.*
import zio.test.*

import RouteRunner.{orDieWithFailure, runRoutes, withCsrf, withSession}

/** The cross-cutting checks in [[RouteSupport]] are the only thing standing between an anonymous request and the data,
  * but they were previously exercised only indirectly through one end-to-end browser test. These drive the real
  * `Routes` values with `runZIO`, no server needed.
  *
  * `AuthRoutes` is what the session and CSRF cases are driven through, because `GET /api/me` and `PUT /api/me/theme`
  * are the skeleton's own read and write behind the `authenticated` aspect. Any route set a feature adds carries the
  * same aspects, so these cases cover it too. What a new route set does need a case of its own for is the shape
  * `AdminRoutes` has below: a *second* context-providing aspect, on a handler that also takes a path parameter.
  */
object RouteGuardsSpec extends ZIOSpecDefault {

  private val repoLayers = {
    TestDataSource.sqlite >>> (
      UserRepository.test ++ SessionRepository.test ++
        OAuthIdentityRepository.test ++ EmailVerificationTokenRepository.test ++ PasswordResetTokenRepository.test ++
        LoginAttemptRepository.test ++ GuestClaimCodeRepository.test ++ AuditLogRepository.test ++
        UsageEventRepository.test ++ MetricsRepository.test ++ WordRepository.test ++ GameRepository.test ++
        GroupRepository.test
    )
  }

  // AdminService is stacked on top of AuthService rather than built beside it: it delegates the resend and unlink
  // paths to the one service that owns them. `OAuthClients` is here because `AuthRoutes` carries the two OAuth
  // redirects as well as the described endpoints — none of the cases below reaches them, but the `Routes` value still
  // has to be constructible.
  private val layer = {
    val base = {
      repoLayers ++ PasswordHasher.live ++ RateLimiter.live ++ BackgroundJobs.live ++ TestCaptchaService.live ++
        GameWordList.live ++ TestAuthLayers.emailAndConfig ++ ((AppConfig.live ++ Client.default) >>> OAuthClients.live)
    }
    base >+> (AuthService.live ++ AuditTrail.live ++ GameService.live) >+>
      (AdminService.live ++ SystemService.live ++ UsageStatsService.live ++ WordService.live)
  }

  /** The one mutating body the CSRF cases are driven with. Its content is beside the point — what matters is that the
    * request is a PUT behind the `authenticated` aspect, carrying a body the endpoint's codec accepts.
    */
  private val darkTheme = """{"theme":"Dark"}"""

  private def signUp(email: String): ZIO[AuthService, Nothing, String] = {
    orDieWithFailure(AuthService.signup(email, "password123")).map(_._2.get)
  }

  private def adminSession(email: String): ZIO[AuthService & AdminService, Nothing, String] = {
    for {
      _       <- orDieWithFailure(AdminService.createUser(AdminActor.system, email, "password123", isAdmin = true))
      session <- orDieWithFailure(AuthService.login(email, "password123")).map(_._2)
    } yield session
  }

  def spec = {
    suite("route guards")(
      test("a state-changing request without the CSRF header is refused") {
        for {
          session  <- signUp("csrf@example.com")
          request   = withSession(Request.put("/api/me/theme", Body.fromString(darkTheme)), session)
          response <- runRoutes(AuthRoutes.routes, request)
        } yield assertTrue(response.status == Status.Forbidden)
      },
      test("the same request with the CSRF header goes through") {
        for {
          session  <- signUp("csrf-ok@example.com")
          request   = withCsrf(withSession(Request.put("/api/me/theme", Body.fromString(darkTheme)), session))
          response <- runRoutes(AuthRoutes.routes, request)
        } yield assertTrue(response.status == Status.Ok)
      },
      // The CSRF aspect is scoped by method rather than attached route by route, so a read has to stay reachable
      // without the header — the frontend's initial page loads don't send one.
      test("a read is not subject to the CSRF header") {
        for {
          session  <- signUp("csrf-read@example.com")
          response <- runRoutes(AuthRoutes.routes, withSession(Request.get("/api/me"), session))
        } yield assertTrue(response.status == Status.Ok)
      },
      // CSRF is checked before the session is looked up, so a cross-site request can't tell a valid
      // session cookie from an invalid one by the status code.
      test("a state-changing request without either the CSRF header or a session is refused, not unauthorized") {
        for {
          response <- runRoutes(AuthRoutes.routes, Request.put("/api/me/theme", Body.fromString(darkTheme)))
        } yield assertTrue(response.status == Status.Forbidden)
      },
      test("a request without a session cookie is unauthorized") {
        for {
          response <- runRoutes(AuthRoutes.routes, Request.get("/api/me"))
        } yield assertTrue(response.status == Status.Unauthorized)
      },
      test("a request carrying an unknown session id is unauthorized") {
        for {
          response <- runRoutes(AuthRoutes.routes, withSession(Request.get("/api/me"), "not-a-real-session"))
        } yield assertTrue(response.status == Status.Unauthorized)
      },
      test("an admin route denies a signed-in non-admin") {
        for {
          session  <- signUp("plain@example.com")
          response <- runRoutes(AdminRoutes.routes, withSession(Request.get("/api/admin/users"), session))
        } yield assertTrue(response.status == Status.Forbidden)
      },
      test("an admin route denies an anonymous caller as unauthorized") {
        for {
          response <- runRoutes(AdminRoutes.routes, Request.get("/api/admin/users"))
        } yield assertTrue(response.status == Status.Unauthorized)
      },
      test("an admin route admits an administrator") {
        for {
          session  <- adminSession("boss@example.com")
          response <- runRoutes(AdminRoutes.routes, withSession(Request.get("/api/admin/users"), session))
        } yield assertTrue(response.status == Status.Ok)
      },
      // `AdminRoutes` is the one route set carrying *two* context-providing aspects — `adminOnly` for the acting
      // administrator and `requestContext` for the peer address, which the audit trail records. That composes at the
      // type level whatever the order, so these drive a mutating route with a path parameter through the real stack:
      // getting it wrong is a `ClassCastException` at request time, not a compile error.
      test("a mutating admin route reaches its handler with both the administrator and the request context") {
        for {
          session  <- adminSession("ops@example.com")
          created  <-
            orDieWithFailure(
              AdminService.createUser(AdminActor.system, "ops-target@example.com", "password123", isAdmin = false)
            )
          request   = withCsrf(withSession(Request.delete(s"/api/admin/users/${created.id}/sessions"), session))
          response <- runRoutes(AdminRoutes.routes, request)
        } yield assertTrue(response.status == Status.NoContent)
      },
      test("a mutating admin route still requires the CSRF header") {
        for {
          session  <- adminSession("ops-csrf@example.com")
          request   = withSession(Request.delete("/api/admin/users/1/sessions"), session)
          response <- runRoutes(AdminRoutes.routes, request)
        } yield assertTrue(response.status == Status.Forbidden)
      },
      test("an unparseable provider segment on the unlink route is a not-found, not a 500") {
        for {
          session  <- adminSession("ops-unlink@example.com")
          created  <-
            orDieWithFailure(
              AdminService.createUser(AdminActor.system, "unlink-me@example.com", "password123", isAdmin = false)
            )
          request   = withCsrf(
                        withSession(Request.delete(s"/api/admin/users/${created.id}/identities/facebook"), session)
                      )
          response <- runRoutes(AdminRoutes.routes, request)
        } yield assertTrue(response.status == Status.NotFound)
      },
      test("the system overview is admin-only too") {
        for {
          plain   <- signUp("not-ops@example.com")
          denied  <- runRoutes(AdminRoutes.routes, withSession(Request.get("/api/admin/system"), plain))
          session <- adminSession("sysadmin@example.com")
          allowed <- runRoutes(AdminRoutes.routes, withSession(Request.get("/api/admin/system"), session))
        } yield assertTrue(denied.status == Status.Forbidden, allowed.status == Status.Ok)
      },
      test("a defect becomes a generic JSON 500 rather than a stack trace in the body") {
        val boom                               = new RuntimeException("relation \"users\" does not exist")
        val dyingRoutes: Routes[Any, Response] = {
          Routes(Method.GET / "api" / "boom" -> handler((_: Request) => ZIO.die(boom)))
        }
        for {
          response <- runRoutes(dyingRoutes, Request.get("/api/boom"))
          body     <- response.body.asString
        } yield assertTrue(
          response.status == Status.InternalServerError,
          body ==
            """{"error":{"key":"error.internal","args":[]},"message":"Internal server error","fieldErrors":{}}""",
          !body.contains("relation"),
        )
      },
      // `optionalUser` is the one aspect that never refuses a request. The three cases it has to tell apart are no
      // cookie at all, a cookie naming a session that does not exist, and a real one — the first two being the same
      // answer, since neither identifies anybody.
      test("the vocabulary reads answer with no session, with a junk one, and with a real one") {
        for {
          session   <- signUp("optional-user@example.com")
          anonymous <- runRoutes(WordRoutes.routes, Request.get("/api/words"))
          junk      <- runRoutes(WordRoutes.routes, withSession(Request.get("/api/words"), "not-a-session"))
          real      <- runRoutes(WordRoutes.routes, withSession(Request.get("/api/words"), session))
          // The writes on the same `Routes` value are still guarded, which is what makes the split worth pinning.
          write     <- runRoutes(WordRoutes.routes, withCsrf(Request.get("/api/tags")))
        } yield assertTrue(
          anonymous.status == Status.Ok,
          junk.status == Status.Ok,
          real.status == Status.Ok,
          write.status == Status.Unauthorized,
        )
      },
      // zio-http's own not-found response is `Response.error(NotFound, path)`: no JSON, and the requested path
      // echoed back into the body. Every other error this API can answer with is an `ErrorResponse` object.
      test("a path that matches no route is a JSON 404 that does not echo the path") {
        for {
          response <- runRoutes(AuthRoutes.routes, Request.get("/api/no-such-thing"))
          body     <- response.body.asString
        } yield assertTrue(
          response.status == Status.NotFound,
          body == """{"error":{"key":"error.notFound","args":[]},"message":"Not found","fieldErrors":{}}""",
          !body.contains("no-such-thing"),
        )
      },
      // `Routes#runZIO` opens a scope per request, so the suite has to supply one.
    ).provide(layer, Scope.default) @@ TestAspect.timeout(60.seconds)
  }
}
