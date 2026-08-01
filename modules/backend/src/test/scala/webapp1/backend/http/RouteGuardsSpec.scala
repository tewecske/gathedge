package webapp1.backend.http

import webapp1.backend.TestDataSource
import webapp1.backend.db.{
  SessionRepository,
  SqliteSessionRepository,
  SqliteTodoRepository,
  SqliteUserRepository,
  TodoRepository,
  UserRepository,
}
import webapp1.backend.security.{PasswordHasher, SessionAuth}
import webapp1.backend.service.{
  AdminService,
  AdminServiceLive,
  AuthService,
  AuthServiceLive,
  InMemoryRateLimiter,
  TodoService,
  TodoServiceLive,
}
import webapp1.shared.validation.Validation
import zio.*
import zio.http.*
import zio.test.*

/** The cross-cutting checks in [[RouteSupport]] are the only thing standing between an anonymous request and the data,
  * but they were previously exercised only indirectly through one end-to-end browser test. These drive the real
  * `Routes` values with `runZIO`, no server needed.
  */
object RouteGuardsSpec extends ZIOSpecDefault {

  private val repoLayers: ZLayer[Any, Throwable, UserRepository & SessionRepository & TodoRepository] =
    TestDataSource.sqlite >>> (SqliteUserRepository.live ++ SqliteSessionRepository.live ++ SqliteTodoRepository.live)

  private val layer: ZLayer[Any, Throwable, AuthService & AdminService & TodoService] = {
    (repoLayers ++ PasswordHasher.live ++ InMemoryRateLimiter.live) >>>
      (AuthServiceLive.live ++ AdminServiceLive.live ++ TodoServiceLive.live)
  }

  private def withCsrf(request: Request): Request = {
    request.addHeader(SessionAuth.csrfHeaderName, SessionAuth.csrfHeaderValue)
  }

  private def withSession(request: Request, sessionId: String): Request = {
    request.addCookie(Cookie.Request(SessionAuth.cookieName, sessionId))
  }

  // The service failures here are all "the fixture couldn't be set up", never the thing under test.
  private def orDieWithFailure[R, E, A](effect: ZIO[R, E, A]): ZIO[R, Nothing, A] = {
    effect.orDieWith(failure => new RuntimeException(s"test setup failed: $failure"))
  }

  private def signUp(email: String): ZIO[AuthService, Nothing, String] = {
    ZIO.serviceWithZIO[AuthService](service => orDieWithFailure(service.signup(email, "password123")).map(_._2))
  }

  private def adminSession(email: String): ZIO[AuthService & AdminService, Nothing, String] = {
    for {
      adminService <- ZIO.service[AdminService]
      authService <- ZIO.service[AuthService]
      _ <- orDieWithFailure(adminService.createUser(0L, email, "password123", isAdmin = true))
      session <- orDieWithFailure(authService.login(email, "password123")).map(_._2)
    } yield session
  }

  def spec = {
    suite("route guards")(
      test("a state-changing request without the CSRF header is refused") {
        for {
          session <- signUp("csrf@example.com")
          request = withSession(Request.post("/api/todos", Body.fromString("""{"text":"buy milk"}""")), session)
          response <- TodoRoutes.routes.runZIO(request)
        } yield assertTrue(response.status == Status.Forbidden)
      },
      test("the same request with the CSRF header goes through") {
        for {
          session <- signUp("csrf-ok@example.com")
          request = withCsrf(
            withSession(Request.post("/api/todos", Body.fromString("""{"text":"buy milk"}""")), session)
          )
          response <- TodoRoutes.routes.runZIO(request)
        } yield assertTrue(response.status == Status.Created)
      },
      test("a request without a session cookie is unauthorized") {
        for {
          response <- TodoRoutes.routes.runZIO(Request.get("/api/todos"))
        } yield assertTrue(response.status == Status.Unauthorized)
      },
      test("a request carrying an unknown session id is unauthorized") {
        for {
          response <- TodoRoutes.routes.runZIO(withSession(Request.get("/api/todos"), "not-a-real-session"))
        } yield assertTrue(response.status == Status.Unauthorized)
      },
      test("an admin route denies a signed-in non-admin") {
        for {
          session <- signUp("plain@example.com")
          response <- AdminRoutes.routes.runZIO(withSession(Request.get("/api/admin/users"), session))
        } yield assertTrue(response.status == Status.Forbidden)
      },
      test("an admin route denies an anonymous caller as unauthorized") {
        for {
          response <- AdminRoutes.routes.runZIO(Request.get("/api/admin/users"))
        } yield assertTrue(response.status == Status.Unauthorized)
      },
      test("an admin route admits an administrator") {
        for {
          session <- adminSession("boss@example.com")
          response <- AdminRoutes.routes.runZIO(withSession(Request.get("/api/admin/users"), session))
        } yield assertTrue(response.status == Status.Ok)
      },
      // Over-length input used to reach the database and surface as a 500 with a stack trace in the
      // body; it has to come back as an ordinary validation failure.
      test("text longer than the column width is a 400, not a 500") {
        val tooLong = "a" * (Validation.maxTextLength + 1)
        for {
          session <- signUp("long-text@example.com")
          body = Body.fromString(s"""{"text":"$tooLong"}""")
          request = withCsrf(withSession(Request.post("/api/todos", body), session))
          response <- TodoRoutes.routes.runZIO(request)
        } yield assertTrue(response.status == Status.BadRequest)
      },
      test("a defect becomes a generic JSON 500 rather than a stack trace in the body") {
        val boom = new RuntimeException("relation \"users\" does not exist")
        val dyingRoutes = {
          RouteSupport.handleDefects(Routes(Method.GET / "api" / "boom" -> handler((_: Request) => ZIO.die(boom))))
        }
        for {
          response <- dyingRoutes.runZIO(Request.get("/api/boom"))
          body <- response.body.asString
        } yield assertTrue(
          response.status == Status.InternalServerError,
          body == """{"message":"Internal server error","fieldErrors":{}}""",
          !body.contains("relation"),
        )
      },
      // `Routes#runZIO` opens a scope per request, so the suite has to supply one.
    ).provide(layer, Scope.default)
  }
}
