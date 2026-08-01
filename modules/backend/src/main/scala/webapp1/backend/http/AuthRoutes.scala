package webapp1.backend.http

import webapp1.backend.config.AppConfig
import webapp1.backend.security.SessionAuth
import webapp1.backend.service.{AuthFailure, AuthService, GoogleOAuthClient}
import webapp1.shared.dto.{AuthResponse, LoginRequest, SignupRequest, UpdateThemeRequest}
import zio.*
import zio.http.*

import JsonSupport.*
import RouteSupport.{authenticatedUser, csrfCheck}

object AuthRoutes {

  /** The socket peer, deliberately not `X-Forwarded-For`: that header is attacker-controlled unless a trusted-proxy
    * list is configured, and letting it pick the rate-limit key would hand out a fresh budget per request. Behind a
    * reverse proxy this collapses to the proxy's address, leaving the per-email limit to do the work.
    */
  private def clientIp(request: Request): Option[String] = {
    request.remoteAddress.map(_.getHostAddress)
  }

  private def authFailureResponse(failure: AuthFailure): Response = {
    failure match {
      case AuthFailure.InvalidCredentials =>
        errorResponse(Status.Unauthorized, "Invalid email or password")
      case AuthFailure.EmailAlreadyRegistered =>
        errorResponse(Status.Conflict, "Email already registered", Map("email" -> "Email already registered"))
      case AuthFailure.ValidationError(fieldErrors) =>
        errorResponse(Status.BadRequest, "Validation failed", fieldErrors)
      case AuthFailure.RateLimited =>
        errorResponse(Status.TooManyRequests, "Too many attempts. Try again later.")
      case AuthFailure.GoogleAuthFailed(reason) =>
        errorResponse(Status.BadRequest, s"Google sign-in failed: $reason")
    }
  }

  private val signupRoute = {
    Method.POST / "api" / "auth" / "signup" ->
      handler { (request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            body <- readJson[SignupRequest](request)
            authService <- ZIO.service[AuthService]
            cfg <- ZIO.service[AppConfig]
            result <- authService.signup(body.email, body.password, clientIp(request)).mapError(authFailureResponse)
          } yield {
            val (user, sessionId) = result
            jsonResponse(Status.Created, AuthResponse(user)).addCookie(
              SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure)
            )
          }
        ).merge
      }
  }

  private val loginRoute = {
    Method.POST / "api" / "auth" / "login" ->
      handler { (request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            body <- readJson[LoginRequest](request)
            authService <- ZIO.service[AuthService]
            cfg <- ZIO.service[AppConfig]
            result <- authService.login(body.email, body.password, clientIp(request)).mapError(authFailureResponse)
          } yield {
            val (user, sessionId) = result
            jsonResponse(Status.Ok, AuthResponse(user)).addCookie(
              SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure)
            )
          }
        ).merge
      }
  }

  private val logoutRoute = {
    Method.POST / "api" / "auth" / "logout" ->
      handler { (request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
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
        ).merge
      }
  }

  private val meRoute = {
    Method.GET / "api" / "me" ->
      handler { (request: Request) =>
        authenticatedUser(request).map(user => jsonResponse(Status.Ok, AuthResponse(user))).merge
      }
  }

  private val updateThemeRoute = {
    Method.PUT / "api" / "me" / "theme" ->
      handler { (request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            user <- authenticatedUser(request)
            body <- readJson[UpdateThemeRequest](request)
            authService <- ZIO.service[AuthService]
            // A failure here is a bug or a dead database, not something the caller can act on:
            // die and let Main's route-level handler log the cause and answer a generic 500.
            updated <- authService.updateTheme(user.id, body.theme).orDie
          } yield jsonResponse(Status.Ok, AuthResponse(updated))
        ).merge
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
      handler { (request: Request) =>
        (
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
        ).merge
      }
  }

  private val googleCallbackRoute = {
    Method.GET / "api" / "auth" / "google" / "callback" ->
      handler { (request: Request) =>
        (
          for {
            cfg <- ZIO.service[AppConfig]
            _ <-
              ZIO
                .unless(cfg.isGoogleOAuthConfigured)(
                  ZIO.fail(errorResponse(Status.NotFound, "Google sign-in is not configured"))
                )
                .unit
            code <- ZIO
              .fromOption(request.queryParam("code"))
              .orElseFail(loginErrorRedirect(cfg, "google_missing_code"))
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
        ).merge
      }
  }

  val routes: Routes[AuthService & GoogleOAuthClient & AppConfig, Nothing] = Routes(
    signupRoute,
    loginRoute,
    logoutRoute,
    meRoute,
    updateThemeRoute,
    googleStartRoute,
    googleCallbackRoute,
  )
}
