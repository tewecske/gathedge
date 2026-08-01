package webapp1.backend.http

import webapp1.backend.config.AppConfig
import webapp1.backend.security.SessionAuth
import webapp1.backend.service.{AuthService, GoogleOAuthClient}
import webapp1.shared.domain.User
import webapp1.shared.dto.{AuthResponse, LoginRequest, SignupRequest, UpdateThemeRequest}
import zio.*
import zio.http.*

import JsonSupport.*

object AuthRoutes {

  /** The socket peer, deliberately not `X-Forwarded-For`: that header is attacker-controlled unless a trusted-proxy
    * list is configured, and letting it pick the rate-limit key would hand out a fresh budget per request. Behind a
    * reverse proxy this collapses to the proxy's address, leaving the per-email limit to do the work.
    */
  private def clientIp(request: Request): Option[String] = {
    request.remoteAddress.map(_.getHostAddress)
  }

  private val signupRoute = {
    Method.POST / "api" / "auth" / "signup" ->
      handler { (request: Request) =>
        for {
          body <- readJson[SignupRequest](request)
          authService <- ZIO.service[AuthService]
          cfg <- ZIO.service[AppConfig]
          result <- authService.signup(body.email, body.password, clientIp(request)).mapError(FailureResponses.auth)
        } yield {
          val (user, sessionId) = result
          jsonResponse(Status.Created, AuthResponse(user)).addCookie(
            SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure)
          )
        }
      }
  }

  private val loginRoute = {
    Method.POST / "api" / "auth" / "login" ->
      handler { (request: Request) =>
        for {
          body <- readJson[LoginRequest](request)
          authService <- ZIO.service[AuthService]
          cfg <- ZIO.service[AppConfig]
          result <- authService.login(body.email, body.password, clientIp(request)).mapError(FailureResponses.auth)
        } yield {
          val (user, sessionId) = result
          jsonResponse(Status.Ok, AuthResponse(user)).addCookie(
            SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure)
          )
        }
      }
  }

  private val logoutRoute = {
    Method.POST / "api" / "auth" / "logout" ->
      handler { (request: Request) =>
        for {
          authService <- ZIO.service[AuthService]
          cfg <- ZIO.service[AppConfig]
          // Logging out with no session cookie is a no-op, not an error.
          _ <-
            SessionAuth.sessionIdFrom(request) match {
              case Some(sid) =>
                authService.logout(sid)
              case None =>
                ZIO.unit
            }
        } yield {
          Response(status = Status.NoContent).addCookie(SessionAuth.expiredSessionCookie(cfg.session.cookieSecure))
        }
      }
  }

  private val meRoute = {
    Method.GET / "api" / "me" ->
      handler { (_: Request) =>
        ZIO.serviceWith[User](user => jsonResponse(Status.Ok, AuthResponse(user)))
      }
  }

  private val updateThemeRoute = {
    Method.PUT / "api" / "me" / "theme" ->
      handler { (request: Request) =>
        for {
          user <- ZIO.service[User]
          body <- readJson[UpdateThemeRequest](request)
          authService <- ZIO.service[AuthService]
          // A failure here is a bug or a dead database, not something the caller can act on:
          // die and let Main's route-level handler log the cause and answer a generic 500.
          updated <- authService.updateTheme(user.id, body.theme).orDie
        } yield jsonResponse(Status.Ok, AuthResponse(updated))
      }
  }

  private val oauthStateCookieName = "oauth_state"

  private def oauthStateCookie(state: String, secure: Boolean, maxAge: Duration): Cookie.Response = {
    Cookie.Response(
      name = oauthStateCookieName,
      content = state,
      path = Some(Path.root),
      isHttpOnly = true,
      isSecure = secure,
      sameSite = Some(Cookie.SameSite.Lax),
      maxAge = Some(maxAge),
    )
  }

  private def redirectResponse(target: String): Response = {
    URL
      .decode(target)
      .fold(_ => errorResponse(Status.InternalServerError, "Invalid redirect target"), Response.redirect(_))
  }

  /** The Google endpoints are reached by top-level browser navigation, so failures have to come back as a redirect into
    * the SPA (a JSON body would just be rendered as text). The reason travels as a short opaque code rather than an
    * exception message.
    */
  private def loginErrorRedirect(cfg: AppConfig, code: String): Response = {
    redirectResponse(s"${cfg.app.publicBaseUrl}/login?error=$code")
  }

  private val googleStartRoute = {
    Method.GET / "api" / "auth" / "google" / "start" ->
      handler { (_: Request) =>
        for {
          cfg <- ZIO.service[AppConfig]
          _ <-
            ZIO
              .unless(cfg.isGoogleOAuthConfigured)(
                ZIO.fail(errorResponse(Status.NotFound, "Google sign-in is not configured"))
              )
              .unit
          client <- ZIO.service[GoogleOAuthClient]
          state <- Random.nextUUID.map(_.toString)
          url <- ZIO
            .fromEither(URL.decode(client.authorizationUrl(state)))
            .tapErrorCause(cause => ZIO.logErrorCause("Could not build the Google authorization URL", cause))
            .mapError(_ => errorResponse(Status.InternalServerError, "Google sign-in is unavailable"))
        } yield {
          Response.redirect(url).addCookie(oauthStateCookie(state, cfg.session.cookieSecure, 10.minutes))
        }
      }
  }

  private val googleCallbackRoute = {
    Method.GET / "api" / "auth" / "google" / "callback" ->
      handler { (request: Request) =>
        for {
          cfg <- ZIO.service[AppConfig]
          _ <-
            ZIO
              .unless(cfg.isGoogleOAuthConfigured)(
                ZIO.fail(errorResponse(Status.NotFound, "Google sign-in is not configured"))
              )
              .unit
          code <- ZIO.fromOption(request.queryParam("code")).orElseFail(loginErrorRedirect(cfg, "google_missing_code"))
          state <- ZIO
            .fromOption(request.queryParam("state"))
            .orElseFail(loginErrorRedirect(cfg, "google_missing_state"))
          cookieState <- ZIO
            .fromOption(request.cookie(oauthStateCookieName).map(_.content))
            .orElseFail(loginErrorRedirect(cfg, "google_missing_state"))
          _ <- ZIO.unless(state == cookieState)(ZIO.fail(loginErrorRedirect(cfg, "google_state_mismatch"))).unit
          client <- ZIO.service[GoogleOAuthClient]
          authService <- ZIO.service[AuthService]
          identity <- client
            .exchangeAndVerify(code)
            .tapErrorCause(cause => ZIO.logErrorCause("Google code exchange or id_token verification failed", cause))
            .mapError(_ => loginErrorRedirect(cfg, "google_failed"))
          result <- authService.loginWithGoogle(identity).mapError(_ => loginErrorRedirect(cfg, "google_failed"))
        } yield {
          val (_, sessionId) = result
          redirectResponse(cfg.app.publicBaseUrl)
            .addCookie(SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure))
            .addCookie(oauthStateCookie("", cfg.session.cookieSecure, Duration.Zero))
        }
      }
  }

  /** Signing up, signing in and the two Google redirects are reachable without a session by definition; `/api/me` and
    * the theme preference are not. Splitting the two sets is what lets the `authenticated` aspect cover exactly the
    * latter, with the CSRF check spanning both.
    */
  private val anonymousRoutes = {
    Routes(signupRoute, loginRoute, logoutRoute, googleStartRoute, googleCallbackRoute)
  }

  private val sessionRoutes = {
    Routes(meRoute, updateThemeRoute) @@ RouteSupport.authenticated
  }

  val routes: Routes[AuthService & GoogleOAuthClient & AppConfig, Response] = {
    (anonymousRoutes ++ sessionRoutes) @@ RouteSupport.csrf
  }
}
