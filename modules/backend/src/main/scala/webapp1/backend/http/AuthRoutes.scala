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
            result <- authService.signup(body.email, body.password).mapError(authFailureResponse)
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
            result <- authService.login(body.email, body.password).mapError(authFailureResponse)
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
            updated <- authService
              .updateTheme(user.id, body.theme)
              .mapError(err => errorResponse(Status.InternalServerError, err.getMessage))
          } yield jsonResponse(Status.Ok, AuthResponse(updated))
        ).merge
      }
  }

  private val googleStartRoute = {
    Method.GET / "api" / "auth" / "google" / "start" ->
      handler { (request: Request) =>
        (
          for {
            client <- ZIO.service[GoogleOAuthClient]
            cfg <- ZIO.service[AppConfig]
            state <- Random.nextUUID.map(_.toString)
            url <- ZIO.fromEither(URL.decode(client.authorizationUrl(state)))
          } yield {
            val stateCookie = Cookie.Response(
              name = "oauth_state",
              content = state,
              path = Some(Path.root),
              isHttpOnly = true,
              isSecure = cfg.session.cookieSecure,
              sameSite = Some(Cookie.SameSite.Lax),
              maxAge = Some(10.minutes),
            )
            Response.redirect(url).addCookie(stateCookie)
          }
        ).catchAll(err => ZIO.succeed(errorResponse(Status.InternalServerError, err.getMessage)))
      }
  }

  private val googleCallbackRoute = {
    Method.GET / "api" / "auth" / "google" / "callback" ->
      handler { (request: Request) =>
        (
          for {
            code <- ZIO
              .fromOption(request.queryParam("code"))
              .orElseFail(errorResponse(Status.BadRequest, "Missing code"))
            state <- ZIO
              .fromOption(request.queryParam("state"))
              .orElseFail(errorResponse(Status.BadRequest, "Missing state"))
            cookieState <- ZIO
              .fromOption(request.cookie("oauth_state").map(_.content))
              .orElseFail(errorResponse(Status.BadRequest, "Missing state cookie"))
            _ <- ZIO.unless(state == cookieState)(ZIO.fail(errorResponse(Status.BadRequest, "State mismatch"))).unit
            client <- ZIO.service[GoogleOAuthClient]
            authService <- ZIO.service[AuthService]
            cfg <- ZIO.service[AppConfig]
            identity <- client
              .exchangeAndVerify(code)
              .mapError(err => errorResponse(Status.BadGateway, s"Google sign-in failed: ${err.getMessage}"))
            result <- authService.loginWithGoogle(identity).mapError(authFailureResponse)
          } yield {
            val (user, sessionId) = result
            jsonResponse(Status.Ok, AuthResponse(user)).addCookie(
              SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure)
            )
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
