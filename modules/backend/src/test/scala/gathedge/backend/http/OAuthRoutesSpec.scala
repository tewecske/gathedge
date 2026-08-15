package gathedge.backend.http

import gathedge.backend.{TestAuthLayers, TestDataSource}
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
import gathedge.backend.service.{AuthService, OAuthClient, OAuthClients, OAuthIdentity, RateLimiter}
import gathedge.shared.domain.OAuthProvider
import gathedge.shared.dto.{ErrorResponse, IdentitiesResponse, SetPasswordRequest}
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import RouteRunner.{orDieWithFailure, runRoutes, withCsrf, withSession}

/** The `/api/auth/{provider}/…` redirect pair and the account-settings endpoints.
  *
  * The provider is stubbed: what is under test is everything *around* the token exchange — the `oauth_state` cookie
  * that stands in for the CSRF header these two routes cannot carry, the login-vs-link intent that has to survive a
  * round trip through the provider, and which page each outcome lands on. The exchange itself is covered by
  * `MicrosoftOAuthClientSpec`.
  */
object OAuthRoutesSpec extends ZIOSpecDefault {

  /** Returns a fixed identity, so a "code" in these tests is just a marker that the exchange step was reached. */
  private final class StubClient(identity: OAuthIdentity) extends OAuthClient {
    val provider: OAuthProvider                              = identity.provider
    def authorizationUrl(state: String): String              = s"https://provider.example.com/authorize?state=$state"
    def exchangeAndVerify(code: String): Task[OAuthIdentity] = {
      if (code == "bad-code")
        ZIO.fail(new RuntimeException("exchange rejected"))
      else
        ZIO.succeed(identity)
    }
  }

  private val stubSubject = "stub-subject-1"
  private val stubEmail   = "stub@example.com"

  /** Google is stubbed and Microsoft deliberately absent, so the "unconfigured provider" case is reachable without
    * touching config.
    */
  private val stubClients: ULayer[OAuthClients] = {
    ZLayer.succeed {
      new OAuthClients {
        private val google                                            = {
          new StubClient(OAuthIdentity(OAuthProvider.Google, stubSubject, stubEmail, emailVerified = true))
        }
        def forProvider(provider: OAuthProvider): Option[OAuthClient] = {
          Option.when(provider == OAuthProvider.Google)(google)
        }
      }
    }
  }

  private val layer = {
    val repos = {
      TestDataSource.sqlite >>> (
        UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
          EmailVerificationTokenRepository.test ++ PasswordResetTokenRepository.test ++ LoginAttemptRepository.test ++
          GuestClaimCodeRepository.test ++ AuditLogRepository.test
      )
    }
    AppConfig.live ++ stubClients ++ (
      (repos ++ PasswordHasher.live ++ RateLimiter.live ++ TestAuthLayers.emailAndConfig) >>>
        AuthService.live
    )
  }

  private val stateCookieName = "oauth_state"

  /** `Request.get(String)` does not split a query string off the path — the whole thing becomes one path, which then
    * matches no route — so the URL is decoded explicitly.
    */
  private def urlOf(raw: String): URL = {
    URL.decode(raw).getOrElse(throw new IllegalArgumentException(s"test URL does not parse: $raw"))
  }

  private def callback(
    provider: String,
    query: String,
    stateCookie: Option[String],
    session: Option[String] = None,
  ): Request = {
    val base      = Request.get(urlOf(s"/api/auth/$provider/callback?$query"))
    val withState = stateCookie.fold(base)(value => base.addCookie(Cookie.Request(stateCookieName, value)))
    session.fold(withState)(id => withSession(withState, id))
  }

  private def locationOf(response: Response): String = {
    response.headers(Header.Location).headOption.map(_.renderedValue).getOrElse("")
  }

  private def signUp(email: String): ZIO[AuthService, Nothing, (Long, String)] = {
    orDieWithFailure(AuthService.signup(email, "password123")).map { case (user, session) =>
      (user.id, session.get)
    }
  }

  def spec = {
    suite("OAuth routes")(
      test("an unknown provider segment is a 404, not a started flow") {
        for {
          response <- runRoutes(AuthRoutes.routes, Request.get("/api/auth/myspace/start"))
        } yield assertTrue(response.status == Status.NotFound)
      },
      // A configured-but-absent provider answers the same 404 as an unknown one: which providers a
      // deployment half-configured is not worth reporting.
      test("a provider with no credentials configured is also a 404") {
        for {
          response <- runRoutes(AuthRoutes.routes, Request.get("/api/auth/microsoft/start"))
        } yield assertTrue(response.status == Status.NotFound)
      },
      test("start redirects to the provider and sets an HttpOnly state cookie holding nonce, intent and locale") {
        for {
          response <- runRoutes(AuthRoutes.routes, Request.get("/api/auth/google/start"))
          cookie    = response.headers(Header.SetCookie).map(_.value).find(_.name == stateCookieName)
        } yield {
          assertTrue(
            response.status.isRedirection,
            locationOf(response).startsWith("https://provider.example.com/authorize"),
            cookie.exists(_.isHttpOnly),
            // nonce|intent|locale, and the nonce is the only part the provider ever echoes back.
            cookie.exists(_.content.split('|').toList.drop(1) == List("login", "en")),
          )
        }
      },
      test("?link=1 records the link intent in the cookie instead") {
        for {
          response <- runRoutes(AuthRoutes.routes, Request.get(urlOf("/api/auth/google/start?link=1")))
          cookie    = response.headers(Header.SetCookie).map(_.value).find(_.name == stateCookieName)
        } yield assertTrue(cookie.exists(_.content.split('|').lift(1).contains("link")))
      },
      // The locale has to survive the round trip through the provider for the same reason the intent
      // does: every exit is a redirect into the SPA, and the SPA's language is its URL prefix. Nothing
      // else carries it — a top-level navigation sends no `X-Locale`.
      test("?locale= is recorded in the state cookie") {
        for {
          response <- runRoutes(AuthRoutes.routes, Request.get(urlOf("/api/auth/google/start?locale=hu")))
          cookie    = response.headers(Header.SetCookie).map(_.value).find(_.name == stateCookieName)
        } yield assertTrue(cookie.exists(_.content.split('|').lift(2).contains("hu")))
      },
      test("a failure on a flow started in Hungarian lands on the Hungarian sign-in page") {
        for {
          response <- runRoutes(
                        AuthRoutes.routes,
                        callback("google", "code=c&state=attacker-nonce", Some("real-nonce|login|hu")),
                      )
        } yield assertTrue(locationOf(response).endsWith("/hu/sign-in?error=state_mismatch"))
      },
      // A cookie written before this build has no third part; the flow was already in flight across a
      // deploy, and an English landing page is the right amount of wrong for that.
      test("a state cookie with no locale part falls back to the default") {
        for {
          response <- runRoutes(
                        AuthRoutes.routes,
                        callback("google", "code=c&state=attacker-nonce", Some("real-nonce|login")),
                      )
        } yield assertTrue(locationOf(response).endsWith("/en/sign-in?error=state_mismatch"))
      },
      // The state cookie is the only thing standing in for the CSRF header on this route, so a callback
      // whose `state` does not match it must not be allowed to mint a session.
      test("a callback whose state does not match the cookie is refused") {
        for {
          response <- runRoutes(
                        AuthRoutes.routes,
                        callback("google", "code=c&state=attacker-nonce", Some("real-nonce|login")),
                      )
          cookie    = response.headers(Header.SetCookie).map(_.value).find(_.name == SessionAuth.cookieName)
        } yield {
          assertTrue(locationOf(response).endsWith("/sign-in?error=state_mismatch"), cookie.isEmpty)
        }
      },
      test("a callback with no state cookie at all is refused") {
        for {
          response <- runRoutes(AuthRoutes.routes, callback("google", "code=c&state=n", None))
        } yield assertTrue(locationOf(response).endsWith("/sign-in?error=missing_state"))
      },
      test("a successful login callback sets the session cookie and lands on the app root") {
        for {
          response <- runRoutes(AuthRoutes.routes, callback("google", "code=c&state=n", Some("n|login")))
          session   = response.headers(Header.SetCookie).map(_.value).find(_.name == SessionAuth.cookieName)
        } yield {
          assertTrue(session.exists(_.content.nonEmpty), !locationOf(response).contains("error="))
        }
      },
      test("a failed exchange lands back on sign-in rather than leaking the exception") {
        for {
          response <- runRoutes(AuthRoutes.routes, callback("google", "code=bad-code&state=n", Some("n|login")))
        } yield {
          assertTrue(
            locationOf(response).endsWith("/sign-in?error=failed"),
            !locationOf(response).contains("exchange rejected"),
          )
        }
      },
      // The never-auto-link rule, seen from the route: the user is sent back with the one code the
      // sign-in page turns into "…then link it from Settings".
      test("a login callback for an email owned by another account redirects with account_exists") {
        for {
          _        <- signUp(stubEmail)
          response <- runRoutes(AuthRoutes.routes, callback("google", "code=c&state=n", Some("n|login")))
          session   = response.headers(Header.SetCookie).map(_.value).find(_.name == SessionAuth.cookieName)
        } yield {
          assertTrue(locationOf(response).endsWith("/sign-in?error=account_exists"), session.isEmpty)
        }
      },
      test("a link callback with no session redirects to settings rather than answering 401") {
        for {
          response <- runRoutes(AuthRoutes.routes, callback("google", "code=c&state=n", Some("n|link")))
        } yield assertTrue(locationOf(response).endsWith("/settings?error=link_requires_session"))
      },
      test("a link callback with a session attaches the identity and that identity then signs in") {
        for {
          created          <- signUp("linker@example.com")
          (userId, session) = created
          linked           <- runRoutes(AuthRoutes.routes, callback("google", "code=c&state=n", Some("n|link"), Some(session)))
          identities       <- AuthService.listIdentities(userId)
          // The same subject now logs in, and must land in the account it was linked to.
          loggedIn         <- orDieWithFailure(
                                AuthService.loginWithOAuth(
                                  OAuthIdentity(OAuthProvider.Google, stubSubject, stubEmail, emailVerified = true)
                                )
                              )
        } yield {
          assertTrue(
            locationOf(linked).endsWith("/settings?linked=google"),
            identities.map(_.provider) == List(OAuthProvider.Google),
            loggedIn._1.id == userId,
          )
        }
      },
      test("the identities endpoint reports the linked providers, whether a password is set, and what is available") {
        for {
          created  <- signUp("identities@example.com")
          response <- runRoutes(AuthRoutes.routes, withSession(Request.get("/api/me/identities"), created._2))
          body     <- response.body.asString
        } yield {
          assertTrue(
            response.status == Status.Ok,
            body.fromJson[IdentitiesResponse].map(_.identities) == Right(Nil),
            body.fromJson[IdentitiesResponse].map(_.hasPassword) == Right(true),
          )
        }
      },
      test("the identities endpoint needs a session") {
        for {
          response <- runRoutes(AuthRoutes.routes, Request.get("/api/me/identities"))
        } yield assertTrue(response.status == Status.Unauthorized)
      },
      test("unlinking a provider that is not linked is a 400, not a silent success") {
        for {
          created  <- signUp("nothing-linked@example.com")
          request   = withCsrf(withSession(Request.delete("/api/me/identities/google"), created._2))
          response <- runRoutes(AuthRoutes.routes, request)
        } yield assertTrue(response.status == Status.BadRequest)
      },
      test("an unparseable provider segment on unlink is a 400 naming what was wrong") {
        for {
          created  <- signUp("bad-segment@example.com")
          request   = withCsrf(withSession(Request.delete("/api/me/identities/myspace"), created._2))
          response <- runRoutes(AuthRoutes.routes, request)
          body     <- response.body.asString
        } yield {
          assertTrue(
            response.status == Status.BadRequest,
            body.fromJson[ErrorResponse].map(_.message).exists(_.contains("myspace")),
          )
        }
      },
      // The lockout guard over HTTP: a social-only account unlinking its one identity gets a 409, not a
      // 204 that would leave it permanently unreachable.
      test("unlinking the last credential of a social-only account is a 409") {
        for {
          created  <- orDieWithFailure(
                        AuthService.loginWithOAuth(
                          OAuthIdentity(OAuthProvider.Google, "social-only-subject", "social-only@example.com", true)
                        )
                      )
          request   = withCsrf(withSession(Request.delete("/api/me/identities/google"), created._2))
          response <- runRoutes(AuthRoutes.routes, request)
        } yield assertTrue(response.status == Status.Conflict)
      },
      test("setting a password answers 204 and then permits password login") {
        for {
          created  <- orDieWithFailure(
                        AuthService.loginWithOAuth(
                          OAuthIdentity(OAuthProvider.Google, "needs-password", "needs-password@example.com", true)
                        )
                      )
          body      = SetPasswordRequest(None, "password123").toJson
          request   = withCsrf(withSession(Request.put("/api/me/password", Body.fromString(body)), created._2))
          response <- runRoutes(AuthRoutes.routes, request)
          loggedIn <- AuthService.login("needs-password@example.com", "password123").either
        } yield assertTrue(response.status == Status.NoContent, loggedIn.isRight)
      },
      test("a password change with the wrong current password is a 400 against that field") {
        for {
          created  <- signUp("wrong-current@example.com")
          body      = SetPasswordRequest(Some("not-the-password"), "newpassword123").toJson
          request   = withCsrf(withSession(Request.put("/api/me/password", Body.fromString(body)), created._2))
          response <- runRoutes(AuthRoutes.routes, request)
          parsed   <- response.body.asString.map(_.fromJson[ErrorResponse])
        } yield {
          assertTrue(
            response.status == Status.BadRequest,
            parsed.map(_.fieldErrors.keySet) == Right(Set("currentPassword")),
          )
        }
      },
      test("the settings mutations still require the CSRF header") {
        for {
          created  <- signUp("csrf-settings@example.com")
          response <- runRoutes(AuthRoutes.routes, withSession(Request.delete("/api/me/identities/google"), created._2))
        } yield assertTrue(response.status == Status.Forbidden)
      },
    ).provide(layer, Scope.default) @@ TestAspect.sequential
  }
}
