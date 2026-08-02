package webapp1.backend.http

import webapp1.backend.config.AppConfig
import webapp1.backend.security.SessionAuth
import webapp1.backend.service.{AuthService, GoogleOAuthClient}
import webapp1.shared.api.AuthEndpoints
import webapp1.shared.domain.User
import webapp1.shared.dto.{AuthResponse, LoginRequest, SignupRequest, UpdateThemeRequest}
import zio.*
import zio.http.*

import JsonSupport.*
import RouteSupport.RequestContext

/** Sessions, plus the Google sign-in redirects.
  *
  * The five JSON endpoints are implemented against `shared`'s `AuthEndpoints`, so the paths, the status codes and the
  * bodies are not written here. The `Set-Cookie` header *is* part of those descriptions — see
  * `ApiEndpoint.sessionCookie` for why it is described as optional — which is what lets a handler return a plain value
  * and still set the session cookie.
  *
  * The two Google routes stay on the imperative DSL. Both are reached by top-level browser navigation, so success is a
  * 302 into the SPA and so is every failure: the reason travels as a short opaque code in the query string, because a
  * JSON body would just be rendered as text in the address bar. An `Endpoint` describes a request/response *body*
  * protocol, and neither route has one; describing them would mean a redirect codec in the success channel and a second
  * one in the error channel, expressing less than the handlers below do. They are also the one pair of routes exempt
  * from the CSRF header (the OAuth callback cannot carry a custom header), protected by the `oauth_state`
  * cookie/query-param match instead. Consequently they are the only endpoints missing from the OpenAPI document.
  */
object AuthRoutes {

  private def sessionCookie(sessionId: String, cfg: AppConfig): Option[Header.SetCookie] = {
    Some(Header.SetCookie(SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure)))
  }

  private val signupRoute = {
    AuthEndpoints
      .signup
      .implementHandler(
        handler { (body: SignupRequest) =>
          for {
            context <- ZIO.service[RequestContext]
            authService <- ZIO.service[AuthService]
            cfg <- ZIO.service[AppConfig]
            result <- authService.signup(body.email, body.password, context.clientIp).mapError(ApiFailures.auth)
          } yield (AuthResponse(result._1), sessionCookie(result._2, cfg))
        }
      )
  }

  private val loginRoute = {
    AuthEndpoints
      .login
      .implementHandler(
        handler { (body: LoginRequest) =>
          for {
            context <- ZIO.service[RequestContext]
            authService <- ZIO.service[AuthService]
            cfg <- ZIO.service[AppConfig]
            result <- authService.login(body.email, body.password, context.clientIp).mapError(ApiFailures.auth)
          } yield (AuthResponse(result._1), sessionCookie(result._2, cfg))
        }
      )
  }

  private val logoutRoute = {
    AuthEndpoints
      .logout
      .implementHandler(
        handler { (_: Unit) =>
          for {
            context <- ZIO.service[RequestContext]
            authService <- ZIO.service[AuthService]
            cfg <- ZIO.service[AppConfig]
            // Logging out with no session cookie is a no-op, not an error.
            _ <- ZIO.foreachDiscard(context.sessionId)(authService.logout)
          } yield Some(Header.SetCookie(SessionAuth.expiredSessionCookie(cfg.session.cookieSecure)))
        }
      )
  }

  private val meRoute = {
    AuthEndpoints.me.implementHandler(handler((_: Unit) => ZIO.serviceWith[User](user => AuthResponse(user))))
  }

  private val updateThemeRoute = {
    AuthEndpoints
      .updateTheme
      .implementHandler(
        handler { (body: UpdateThemeRequest) =>
          for {
            user <- ZIO.service[User]
            authService <- ZIO.service[AuthService]
            // A failure here is a bug or a dead database, not something the caller can act on:
            // die and let Main's route-level handler log the cause and answer a generic 500.
            updated <- authService.updateTheme(user.id, body.theme).orDie
          } yield AuthResponse(updated)
        }
      )
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

  /** The reason a Google sign-in failed travels as a short opaque code rather than an exception message. */
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

  /** Signing up, signing in and signing out are reachable without a session by definition; `/api/me` and the theme
    * preference are not. Splitting the sets is what lets the `authenticated` aspect cover exactly the latter and the
    * `requestContext` aspect exactly the former, with the CSRF check spanning everything.
    */
  private val anonymousRoutes = {
    Routes(signupRoute, loginRoute, logoutRoute) @@ RouteSupport.requestContext
  }

  private val sessionRoutes = {
    Routes(meRoute, updateThemeRoute) @@ RouteSupport.authenticated
  }

  private val googleRoutes = {
    Routes(googleStartRoute, googleCallbackRoute)
  }

  val routes: Routes[AuthService & GoogleOAuthClient & AppConfig, Response] = {
    (anonymousRoutes ++ sessionRoutes ++ googleRoutes) @@ RouteSupport.csrf
  }
}
