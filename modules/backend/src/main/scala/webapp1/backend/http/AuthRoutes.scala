package webapp1.backend.http

import webapp1.backend.config.AppConfig
import webapp1.backend.security.SessionAuth
import webapp1.backend.service.{AuthFailure, AuthService, OAuthClients}
import webapp1.shared.api.AuthEndpoints
import webapp1.shared.domain.{OAuthProvider, User}
import webapp1.shared.dto.{
  AuthResponse,
  IdentitiesResponse,
  LoginRequest,
  ProvidersResponse,
  SetPasswordRequest,
  SignupRequest,
  UpdateThemeRequest,
}
import zio.*
import zio.http.*

import JsonSupport.*
import OAuthProvider.wire
import RouteSupport.RequestContext

/** Sessions, account settings, and the social sign-in redirects.
  *
  * The eight JSON endpoints are implemented against `shared`'s `AuthEndpoints`, so the paths, the status codes and the
  * bodies are not written here. The `Set-Cookie` header *is* part of those descriptions — see
  * `ApiEndpoint.sessionCookie` for why it is described as optional — which is what lets a handler return a plain value
  * and still set the session cookie.
  *
  * The two OAuth routes stay on the imperative DSL. Both are reached by top-level browser navigation, so success is a
  * 302 into the SPA and so is every failure: the reason travels as a short opaque code in the query string, because a
  * JSON body would just be rendered as text in the address bar. An `Endpoint` describes a request/response *body*
  * protocol, and neither route has one; describing them would mean a redirect codec in the success channel and a second
  * one in the error channel, expressing less than the handlers below do. They are also the one pair of routes exempt
  * from the CSRF header (the OAuth callback cannot carry a custom header), protected by the `oauth_state`
  * cookie/query-param match instead. Consequently they are the only endpoints missing from the OpenAPI document.
  *
  * The provider is a path segment (`/api/auth/{provider}/start`), so one pair of routes serves every provider in
  * `OAuthProvider`; an unknown or unconfigured one answers 404 rather than starting a flow that cannot finish.
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

  private val providersRoute = {
    AuthEndpoints
      .providers
      .implementHandler(
        handler((_: Unit) => ZIO.serviceWith[AppConfig](cfg => ProvidersResponse(cfg.configuredOAuthProviders)))
      )
  }

  private val identitiesRoute = {
    AuthEndpoints
      .identities
      .implementHandler(
        handler { (_: Unit) =>
          for {
            user <- ZIO.service[User]
            cfg <- ZIO.service[AppConfig]
            authService <- ZIO.service[AuthService]
            identities <- authService.listIdentities(user.id)
            hasPassword <- authService.hasPassword(user.id)
          } yield IdentitiesResponse(identities, hasPassword, cfg.configuredOAuthProviders)
        }
      )
  }

  private val unlinkIdentityRoute = {
    AuthEndpoints
      .unlinkIdentity
      .implementHandler(
        handler { (segment: String) =>
          for {
            user <- ZIO.service[User]
            authService <- ZIO.service[AuthService]
            provider <- ZIO
              .fromOption(OAuthProvider.fromString(segment))
              .orElseFail(ApiFailures.auth(AuthFailure.OAuthFailed(s"unknown provider '$segment'")))
            _ <- authService.unlinkOAuth(user.id, provider).mapError(ApiFailures.auth)
          } yield ()
        }
      )
  }

  private val setPasswordRoute = {
    AuthEndpoints
      .setPassword
      .implementHandler(
        handler { (body: SetPasswordRequest) =>
          for {
            user <- ZIO.service[User]
            authService <- ZIO.service[AuthService]
            _ <- authService.setPassword(user.id, body.currentPassword, body.newPassword).mapError(ApiFailures.auth)
          } yield ()
        }
      )
  }

  private val oauthStateCookieName = "oauth_state"

  /** What the callback should do once the provider has vouched for the user: start a session, or attach the identity to
    * the session that is already open. It is chosen at `/start` and has to survive a round trip through the provider,
    * so it rides in the state cookie — see [[oauthStateCookie]].
    */
  private enum OAuthIntent derives CanEqual {
    case Login,
      Link
  }

  private object OAuthIntent {
    def wire(intent: OAuthIntent): String = {
      intent match {
        case Login =>
          "login"
        case Link =>
          "link"
      }
    }

    def parse(s: String): Option[OAuthIntent] = {
      s match {
        case "login" =>
          Some(Login)
        case "link" =>
          Some(Link)
        case _ =>
          None
      }
    }
  }

  /** The cookie holds `nonce|intent`; the `state` query parameter the provider echoes back holds the bare nonce.
    *
    * Splitting them keeps `state` opaque — it stays a value with no meaning to anyone who intercepts it — while the
    * half that actually decides what the callback does never leaves the browser's cookie jar, where it is `HttpOnly`
    * and cannot be rewritten by script or by the provider.
    */
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

  /** The reason a social sign-in failed travels as a short opaque code rather than an exception message. The page it
    * lands on depends on where the user started: a failed login belongs on the sign-in form, a failed link belongs back
    * on the settings page they clicked from.
    */
  private def oauthErrorRedirect(cfg: AppConfig, intent: OAuthIntent, code: String): Response = {
    // These must match `AppRouter`'s patterns: the callback is a browser navigation, so the SPA has to
    // have a route for wherever it lands. (`/login` was the previous target and matched nothing —
    // harmless only because no page ever started the flow.)
    val page = {
      intent match {
        case OAuthIntent.Login =>
          "/sign-in"
        case OAuthIntent.Link =>
          "/settings"
      }
    }
    redirectResponse(s"${cfg.app.publicBaseUrl}$page?error=$code")
  }

  /** Resolves the `{provider}` segment against what this deployment has credentials for. A provider that is unknown and
    * one that is merely unconfigured answer the same 404 on purpose: which providers a deployment supports is already
    * public (the sign-in page lists them), but which ones it has *half*-configured is not worth reporting.
    */
  private def resolveClient(segment: String) = {
    for {
      provider <- ZIO
        .fromOption(OAuthProvider.fromString(segment))
        .orElseFail(errorResponse(Status.NotFound, "Unknown sign-in provider"))
      clients <- ZIO.service[OAuthClients]
      client <- ZIO
        .fromOption(clients.forProvider(provider))
        .orElseFail(errorResponse(Status.NotFound, s"${provider.display} sign-in is not configured"))
    } yield client
  }

  private val oauthStartRoute = {
    Method.GET / "api" / "auth" / string("provider") / "start" ->
      handler { (segment: String, request: Request) =>
        for {
          cfg <- ZIO.service[AppConfig]
          client <- resolveClient(segment)
          // `?link=1` is set by the settings page; anything else starts a plain sign-in. The intent is
          // recorded in the cookie rather than trusted from the callback's query string.
          intent = {
            if (request.queryParam("link").contains("1"))
              OAuthIntent.Link
            else
              OAuthIntent.Login
          }
          nonce <- Random.nextUUID.map(_.toString)
          url <- ZIO
            .fromEither(URL.decode(client.authorizationUrl(nonce)))
            .tapErrorCause(cause =>
              ZIO.logErrorCause(s"Could not build the ${client.provider.wire} authorization URL", cause)
            )
            .mapError(_ => errorResponse(Status.InternalServerError, "Sign-in is unavailable"))
        } yield {
          val cookieValue = s"$nonce|${OAuthIntent.wire(intent)}"
          Response.redirect(url).addCookie(oauthStateCookie(cookieValue, cfg.session.cookieSecure, 10.minutes))
        }
      }
  }

  private val oauthCallbackRoute = {
    Method.GET / "api" / "auth" / string("provider") / "callback" ->
      handler { (segment: String, request: Request) =>
        for {
          cfg <- ZIO.service[AppConfig]
          client <- resolveClient(segment)
          // The cookie is read before anything else can fail, because every failure below needs the intent
          // to know which page to land the user on.
          cookieValue <- ZIO
            .fromOption(request.cookie(oauthStateCookieName).map(_.content))
            .orElseFail(oauthErrorRedirect(cfg, OAuthIntent.Login, "missing_state"))
          cookieParts = cookieValue.split('|')
          intent = cookieParts.lift(1).flatMap(OAuthIntent.parse).getOrElse(OAuthIntent.Login)
          nonce = cookieParts.headOption.getOrElse("")
          state <- ZIO
            .fromOption(request.queryParam("state"))
            .orElseFail(oauthErrorRedirect(cfg, intent, "missing_state"))
          _ <- ZIO.unless(nonce.nonEmpty && state == nonce)(ZIO.fail(oauthErrorRedirect(cfg, intent, "state_mismatch")))
          code <- ZIO.fromOption(request.queryParam("code")).orElseFail(oauthErrorRedirect(cfg, intent, "missing_code"))
          authService <- ZIO.service[AuthService]
          identity <- client
            .exchangeAndVerify(code)
            .tapErrorCause(cause =>
              ZIO.logErrorCause(s"${client.provider.wire} code exchange or id_token verification failed", cause)
            )
            .mapError(_ => oauthErrorRedirect(cfg, intent, "failed"))
          response <-
            intent match {
              case OAuthIntent.Login =>
                authService
                  .loginWithOAuth(identity)
                  .mapBoth(
                    {
                      // The one failure the user can actually act on, and the entire reason the settings
                      // page exists: their email is taken by an account that has never linked this provider.
                      case AuthFailure.OAuthAccountExists(_) =>
                        oauthErrorRedirect(cfg, intent, "account_exists")
                      case _ =>
                        oauthErrorRedirect(cfg, intent, "failed")
                    },
                    { case (_, sessionId) =>
                      redirectResponse(cfg.app.publicBaseUrl).addCookie(
                        SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure)
                      )
                    },
                  )
              case OAuthIntent.Link =>
                for {
                  // The `authenticated` aspect cannot cover this route: its failure is a 401 body, and every
                  // exit from a top-level navigation has to be a redirect. So the session is resolved here.
                  sessionId <- ZIO
                    .fromOption(SessionAuth.sessionIdFrom(request))
                    .orElseFail(oauthErrorRedirect(cfg, intent, "link_requires_session"))
                  user <- authService
                    .currentUser(sessionId)
                    .someOrFail(oauthErrorRedirect(cfg, intent, "link_requires_session"))
                  _ <- authService
                    .linkOAuth(user.id, identity)
                    .mapError {
                      case AuthFailure.OAuthAlreadyLinked =>
                        oauthErrorRedirect(cfg, intent, "already_linked")
                      case _ =>
                        oauthErrorRedirect(cfg, intent, "failed")
                    }
                } yield redirectResponse(s"${cfg.app.publicBaseUrl}/settings?linked=${identity.provider.wire}")
            }
        } yield {
          response.addCookie(oauthStateCookie("", cfg.session.cookieSecure, Duration.Zero))
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

  /** Reachable without a session and needing none of the context the other anonymous routes do — the sign-in form reads
    * it before anyone has signed in.
    */
  private val publicRoutes = {
    Routes(providersRoute)
  }

  private val sessionRoutes = {
    Routes(meRoute, updateThemeRoute, identitiesRoute, unlinkIdentityRoute, setPasswordRoute) @@
      RouteSupport.authenticated
  }

  private val oauthRoutes = {
    Routes(oauthStartRoute, oauthCallbackRoute)
  }

  val routes: Routes[AuthService & OAuthClients & AppConfig, Response] = {
    (anonymousRoutes ++ publicRoutes ++ sessionRoutes ++ oauthRoutes) @@ RouteSupport.csrf
  }
}
