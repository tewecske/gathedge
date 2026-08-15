package gathedge.backend.http

import gathedge.backend.{TestAuthLayers, TestCaptchaService, TestDataSource}
import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  OAuthIdentityRepository,
  PasswordResetTokenRepository,
  SessionRepository,
  UserRepository,
}
import gathedge.backend.security.{PasswordHasher, SessionAuth}
import gathedge.backend.service.{AuthService, OAuthClients, RateLimiter}
import gathedge.shared.dto.{AuthResponse, ErrorResponse, LoginRequest, SignupRequest, SignupResponse}
import zio.*
import zio.http.*
import zio.http.netty.NettyConfig
import zio.http.netty.server.NettyDriver
import zio.json.*
import zio.test.*

/** The one spec that goes over a real socket, using `zio-http-testkit`'s `TestServer`.
  *
  * Everything else drives `Routes` in-process, which is faster but cannot catch anything that lives in the encoding: a
  * cookie whose attributes are wrong, a header the router drops, a body that never gets serialized. The session cookie
  * is the whole auth scheme here, so it is worth exercising through an actual `Client` at least once.
  */
object AuthFlowSpec extends ZIOSpecDefault {

  private val services: ZLayer[Any, Throwable, AuthService & OAuthClients & AppConfig] = {
    val repos = {
      TestDataSource.sqlite >>> (
        UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
          EmailVerificationTokenRepository.test ++ PasswordResetTokenRepository.test ++ LoginAttemptRepository.test ++
          AuditLogRepository.test ++ GuestClaimCodeRepository.test
      )
    }
    AppConfig.live ++ (
      (repos ++ PasswordHasher.live ++ RateLimiter.live ++ TestCaptchaService.live ++ TestAuthLayers.emailAndConfig) >>>
        AuthService.live
    ) ++
      ((AppConfig.live ++ Client.default) >>> OAuthClients.live)
  }

  private def sessionCookie(response: Response): Option[String] = {
    response
      .headers(Header.SetCookie)
      .collectFirst {
        case Header.SetCookie(cookie) if cookie.name == SessionAuth.cookieName =>
          cookie
      }
      .map(_.content)
  }

  private def json[A](value: A)(using JsonEncoder[A]): Body = {
    Body.fromString(value.toJson)
  }

  private val csrfHeader = Header.Custom(SessionAuth.csrfHeaderName, SessionAuth.csrfHeaderValue)

  /** `TestServer` binds to an arbitrary free port, so the address has to be read back off the running server. */
  private val baseUrl: ZIO[Server, Nothing, URL] = {
    ZIO.serviceWithZIO[Server](_.port).map(port => URL.root.port(port))
  }

  def spec = {
    suite("AuthRoutes over HTTP")(
      test("signup sets a session cookie that /api/me accepts, and logout expires it") {
        for {
          client <- ZIO.service[Client]
          _      <- TestServer.addRoutes(RouteSupport.handleFailures(AuthRoutes.routes))
          url    <- baseUrl

          signup     <- client(
                          Request
                            .post(url / "api" / "auth" / "signup", json(SignupRequest("wire@example.com", "password123")))
                            .addHeader(csrfHeader)
                        )
          signupBody <- signup.body.asString
          cookie      = sessionCookie(signup)

          me     <-
            client(
              Request.get(url / "api" / "me").addCookie(Cookie.Request(SessionAuth.cookieName, cookie.getOrElse("")))
            )
          meBody <- me.body.asString

          logout <- client(
                      Request
                        .post(url / "api" / "auth" / "logout", Body.empty)
                        .addHeader(csrfHeader)
                        .addCookie(Cookie.Request(SessionAuth.cookieName, cookie.getOrElse("")))
                    )

          afterLogout <-
            client(
              Request.get(url / "api" / "me").addCookie(Cookie.Request(SessionAuth.cookieName, cookie.getOrElse("")))
            )
        } yield assertTrue(
          signup.status == Status.Created,
          cookie.exists(_.nonEmpty),
          signupBody.fromJson[SignupResponse].map(_.user.email) == Right(Some("wire@example.com")),
          // Verification is off in the test config, so signup still hands back a session.
          signupBody.fromJson[SignupResponse].map(_.signedIn) == Right(true),
          me.status == Status.Ok,
          meBody.fromJson[AuthResponse].map(_.user.email) == Right(Some("wire@example.com")),
          logout.status == Status.NoContent,
          afterLogout.status == Status.Unauthorized,
        )
      },
      test("the session cookie is HttpOnly and SameSite=Lax, so script and cross-site navigation can't use it") {
        for {
          client <- ZIO.service[Client]
          _      <- TestServer.addRoutes(RouteSupport.handleFailures(AuthRoutes.routes))
          url    <- baseUrl
          signup <- client(
                      Request
                        .post(url / "api" / "auth" / "signup", json(SignupRequest("cookie@example.com", "password123")))
                        .addHeader(csrfHeader)
                    )
          cookie  = signup
                      .headers(Header.SetCookie)
                      .collectFirst {
                        case Header.SetCookie(c) if c.name == SessionAuth.cookieName =>
                          c
                      }
        } yield assertTrue(cookie.exists(_.isHttpOnly), cookie.exists(_.sameSite.contains(Cookie.SameSite.Lax)))
      },
      test("a wrong password comes back as a JSON 401, not an empty body") {
        for {
          client <- ZIO.service[Client]
          _      <- TestServer.addRoutes(RouteSupport.handleFailures(AuthRoutes.routes))
          url    <- baseUrl
          _      <- client(
                      Request
                        .post(url / "api" / "auth" / "signup", json(SignupRequest("wrongpw@example.com", "password123")))
                        .addHeader(csrfHeader)
                    )
          login  <-
            client(
              Request
                .post(url / "api" / "auth" / "login", json(LoginRequest("wrongpw@example.com", "not-the-password")))
                .addHeader(csrfHeader)
            )
          body   <- login.body.asString
        } yield assertTrue(
          login.status == Status.Unauthorized,
          body.fromJson[ErrorResponse].map(_.message) == Right("Invalid email or password"),
        )
      },
      test("a POST without the CSRF header never reaches the service") {
        for {
          client   <- ZIO.service[Client]
          _        <- TestServer.addRoutes(RouteSupport.handleFailures(AuthRoutes.routes))
          url      <- baseUrl
          response <-
            client(
              Request.post(url / "api" / "auth" / "signup", json(SignupRequest("nocsrf@example.com", "password123")))
            )
        } yield assertTrue(response.status == Status.Forbidden)
      },
    ).provide(
      services,
      RateLimiter.live,
      TestServer.layer,
      Client.default,
      NettyDriver.customized,
      ZLayer.succeed(Server.Config.default.onAnyOpenPort),
      ZLayer.succeed(NettyConfig.defaultWithFastShutdown),
      Scope.default,
    ) @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential
  }
}
