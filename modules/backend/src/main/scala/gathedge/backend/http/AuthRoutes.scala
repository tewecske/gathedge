package gathedge.backend.http

import gathedge.backend.config.AppConfig
import gathedge.backend.security.{SessionAuth, Tokens}
import gathedge.backend.service.{AuthFailure, AuthService, OAuthClients}
import gathedge.shared.api.AuthEndpoints
import gathedge.shared.domain.{Locale, OAuthProvider, User}
import gathedge.shared.domain.Locale.{code, urlPrefix}
import gathedge.shared.i18n.{MessageKeys, MessageRef}
import gathedge.shared.dto.{
  AuthResponse,
  ClaimCodeResponse,
  ClaimRequest,
  IdentitiesResponse,
  LoginRequest,
  ProvidersResponse,
  ResendVerificationRequest,
  SetPasswordRequest,
  SignupRequest,
  SignupResponse,
  UpdateLocaleRequest,
  UpdateThemeRequest,
  UpgradeRequest,
  VerifyEmailRequest,
}
import zio.*
import zio.http.*

import JsonSupport.*
import OAuthProvider.wire
import RouteSupport.RequestContext

/** Sessions, account settings, and the social sign-in redirects.
  *
  * The JSON endpoints are implemented against `shared`'s `AuthEndpoints`, so the paths, the status codes and the bodies
  * are not written here. The `Set-Cookie` header *is* part of those descriptions — see `ApiEndpoint.sessionCookie` for
  * why it is described as optional — which is what lets a handler return a plain value and still set the session
  * cookie.
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

  /** A guest's cookie outlives a signed-in one by a long way, and has to say so: the row's expiry is
    * `SessionAuth.guestSessionDuration`, and a cookie that expired first would end the account's only way back in while
    * the session behind it was still perfectly good.
    */
  private def guestCookie(sessionId: String, cfg: AppConfig): Option[Header.SetCookie] = {
    Some(
      Header.SetCookie(
        SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure, SessionAuth.guestSessionDuration)
      )
    )
  }

  private val signupRoute = {
    AuthEndpoints.signup
      .implementHandler(
        handler { (body: SignupRequest) =>
          withContext { (context: RequestContext, cfg: AppConfig) =>
            AuthService
              .signup(body.email, body.password, context.clientIp, context.locale)
              .mapError(ApiFailures.auth)
              // No session id means verification is mandatory here: the account exists but stays
              // signed out until the emailed link is followed, so there is no cookie to set.
              .map { case (user, sessionId) =>
                (SignupResponse(user, signedIn = sessionId.isDefined), sessionId.flatMap(sessionCookie(_, cfg)))
              }
          }
        }
      )
  }

  private val loginRoute = {
    AuthEndpoints.login
      .implementHandler(
        handler { (body: LoginRequest) =>
          withContext { (context: RequestContext, cfg: AppConfig) =>
            AuthService
              .login(body.email, body.password, context.clientIp)
              .mapError(ApiFailures.authLogin)
              .map { case (user, sessionId) => (AuthResponse(user), sessionCookie(sessionId, cfg)) }
          }
        }
      )
  }

  /** Mints an account with no credentials, so that tagging a word needs no sign-up.
    *
    * The browser calls this lazily, on the first write a visitor performs — never on a page view, which would be a row
    * per crawler. The locale comes from the request context so the account starts in the language of the page that
    * minted it, which is what an eventual verification email would be written in.
    */
  private val createGuestRoute = {
    AuthEndpoints.createGuest
      .implementHandler(
        handler { (_: Unit) =>
          withContext { (context: RequestContext, cfg: AppConfig) =>
            AuthService
              .createGuest(context.clientIp, context.locale)
              .mapError(ApiFailures.guestMint)
              .map { case (user, sessionId) => (AuthResponse(user), guestCookie(sessionId, cfg)) }
          }
        }
      )
  }

  /** Signs the caller in as the guest account a transfer code belongs to — the second half of "use my words on another
    * machine", and the only credential a guest has to offer.
    */
  private val claimGuestRoute = {
    AuthEndpoints.claimGuest
      .implementHandler(
        handler { (body: ClaimRequest) =>
          withContext { (context: RequestContext, cfg: AppConfig) =>
            AuthService
              .claimGuest(body.code, context.clientIp)
              .mapError(ApiFailures.guestClaim)
              .map { case (user, sessionId) => (AuthResponse(user), guestCookie(sessionId, cfg)) }
          }
        }
      )
  }

  /** Answers a fresh transfer code, once. It is never readable again — the response body is the only place it exists
    * outside the database.
    */
  private val guestCodeRoute = {
    AuthEndpoints.guestCode
      .implementHandler(
        handler { (_: Unit) =>
          withContext { (user: User) =>
            AuthService.issueClaimCode(user.id).mapError(ApiFailures.guestCode).map(ClaimCodeResponse.apply)
          }
        }
      )
  }

  /** Gives the caller's guest account an address and a password, keeping everything it holds. The session it is already
    * signed in with stays valid, so nothing has to be re-entered.
    */
  private val upgradeGuestRoute = {
    AuthEndpoints.upgradeGuest
      .implementHandler(
        handler { (body: UpgradeRequest) =>
          withContext { (user: User, context: RequestContext) =>
            AuthService
              .upgradeGuest(user.id, body.email, body.password, context.locale)
              .mapError(ApiFailures.guestUpgrade)
              .map(AuthResponse.apply)
          }
        }
      )
  }

  private val verifyEmailRoute = {
    AuthEndpoints.verifyEmail
      .implementHandler(
        handler((body: VerifyEmailRequest) => AuthService.verifyEmail(body.token).mapError(ApiFailures.verifyEmail))
      )
  }

  private val resendVerificationRoute = {
    AuthEndpoints.resendVerification
      .implementHandler(
        handler { (body: ResendVerificationRequest) =>
          withContext { (context: RequestContext) =>
            AuthService.resendVerification(body.email, context.clientIp).mapError(ApiFailures.resendVerification)
          }
        }
      )
  }

  private val logoutRoute = {
    AuthEndpoints.logout
      .implementHandler(
        handler { (_: Unit) =>
          withContext { (context: RequestContext, cfg: AppConfig) =>
            // Logging out with no session cookie is a no-op, not an error.
            ZIO
              .foreachDiscard(context.sessionId)(AuthService.logout)
              .as(Some(Header.SetCookie(SessionAuth.expiredSessionCookie(cfg.session.cookieSecure))))
          }
        }
      )
  }

  private val meRoute = {
    AuthEndpoints.me.implementHandler(handler((_: Unit) => withContext((user: User) => AuthResponse(user))))
  }

  private val updateThemeRoute = {
    AuthEndpoints.updateTheme
      .implementHandler(
        handler { (body: UpdateThemeRequest) =>
          withContext { (user: User) =>
            // A failure here is a bug or a dead database, not something the caller can act on:
            // die and let Main's route-level handler log the cause and answer a generic 500.
            AuthService.updateTheme(user.id, body.theme).orDie.map(AuthResponse(_))
          }
        }
      )
  }

  private val updateLocaleRoute = {
    AuthEndpoints.updateLocale
      .implementHandler(
        handler { (body: UpdateLocaleRequest) =>
          withContext { (user: User) =>
            // Persisting only. The page the caller is looking at is already in some language, chosen
            // by its URL prefix; the picker navigates to the other prefix separately.
            AuthService.updateLocale(user.id, body.locale).orDie.map(AuthResponse(_))
          }
        }
      )
  }

  private val providersRoute = {
    AuthEndpoints.providers
      .implementHandler(
        handler((_: Unit) => withContext((cfg: AppConfig) => ProvidersResponse(cfg.configuredOAuthProviders)))
      )
  }

  private val identitiesRoute = {
    AuthEndpoints.identities
      .implementHandler(
        handler { (_: Unit) =>
          withContext { (user: User, cfg: AppConfig) =>
            for {
              identities  <- AuthService.listIdentities(user.id)
              hasPassword <- AuthService.hasPassword(user.id)
            } yield IdentitiesResponse(identities, hasPassword, cfg.configuredOAuthProviders)
          }
        }
      )
  }

  private val unlinkIdentityRoute = {
    AuthEndpoints.unlinkIdentity
      .implementHandler(
        handler { (segment: String) =>
          withContext { (user: User) =>
            for {
              provider <- ZIO
                            .fromOption(OAuthProvider.fromString(segment))
                            .orElseFail(ApiFailures.auth(AuthFailure.OAuthFailed(s"unknown provider '$segment'")))
              _        <- AuthService.unlinkOAuth(user.id, provider).mapError(ApiFailures.auth)
            } yield ()
          }
        }
      )
  }

  private val setPasswordRoute = {
    AuthEndpoints.setPassword
      .implementHandler(
        handler { (body: SetPasswordRequest) =>
          withContext { (user: User) =>
            AuthService.setPassword(user.id, body.currentPassword, body.newPassword).mapError(ApiFailures.auth)
          }
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
        case Link  =>
          "link"
      }
    }

    def parse(s: String): Option[OAuthIntent] = {
      s match {
        case "login" =>
          Some(Login)
        case "link"  =>
          Some(Link)
        case _       =>
          None
      }
    }
  }

  /** The cookie holds `nonce|intent|locale`; the `state` query parameter the provider echoes back holds the bare nonce.
    *
    * Splitting them keeps `state` opaque — it stays a value with no meaning to anyone who intercepts it — while the
    * halves that actually decide what the callback does never leave the browser's cookie jar, where they are `HttpOnly`
    * and cannot be rewritten by script or by the provider.
    *
    * The locale rides along for the same reason the intent does: every exit from the callback is a redirect into the
    * SPA, and the SPA's language is decided by the URL prefix that redirect targets. Nothing else survives the round
    * trip through the provider — the callback carries no `X-Locale` header, since it is a top-level navigation the
    * browser makes rather than a call the client builds.
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
      .fold(
        _ =>
          errorResponse(Status.InternalServerError, MessageRef(MessageKeys.invalidRedirect), "Invalid redirect target"),
        Response.redirect(_),
      )
  }

  /** The reason a social sign-in failed travels as a short opaque code rather than an exception message. The page it
    * lands on depends on where the user started: a failed login belongs on the sign-in form, a failed link belongs back
    * on the settings page they clicked from.
    */
  private def oauthErrorRedirect(cfg: AppConfig, intent: OAuthIntent, locale: Locale, code: String): Response = {
    // These must match `AppRouter`'s patterns, locale prefix included: the callback is a browser
    // navigation, so the SPA has to have a route for wherever it lands. (`/login` was an earlier
    // target and matched nothing — harmless only because no page ever started the flow.)
    val page = {
      intent match {
        case OAuthIntent.Login =>
          "/sign-in"
        case OAuthIntent.Link  =>
          "/settings"
      }
    }
    redirectResponse(s"${cfg.app.publicBaseUrl}${locale.urlPrefix}$page?error=$code")
  }

  /** Resolves the `{provider}` segment against what this deployment has credentials for. A provider that is unknown and
    * one that is merely unconfigured answer the same 404 on purpose: which providers a deployment supports is already
    * public (the sign-in page lists them), but which ones it has *half*-configured is not worth reporting.
    */
  private def resolveClient(segment: String) = {
    for {
      provider    <-
        ZIO
          .fromOption(OAuthProvider.fromString(segment))
          .orElseFail(
            errorResponse(Status.NotFound, MessageRef(MessageKeys.oauthUnknownProvider), "Unknown sign-in provider")
          )
      maybeClient <- OAuthClients.forProvider(provider)
      client      <- ZIO
                       .fromOption(maybeClient)
                       .orElseFail(
                         // Same 404 as an unknown provider, so a half-configured deployment stays unreported.
                         errorResponse(
                           Status.NotFound,
                           MessageRef(MessageKeys.oauthUnknownProvider),
                           s"${provider.display} sign-in is not configured",
                         )
                       )
    } yield client
  }

  private val oauthStartRoute = {
    Method.GET / "api" / "auth" / string("provider") / "start" ->
      handler { (segment: String, request: Request) =>
        withContext { (cfg: AppConfig) =>
          for {
            client <- resolveClient(segment)
            // `?link=1` is set by the settings page; anything else starts a plain sign-in. The intent is
            // recorded in the cookie rather than trusted from the callback's query string.
            intent  = {
              if (request.queryParam("link").contains("1"))
                OAuthIntent.Link
              else
                OAuthIntent.Login
            }
            // A `?locale=` query parameter, because this route is reached by the document navigating
            // rather than by the generated client, so there is no `X-Locale` header to read. The SPA
            // puts the prefix it is running under here; `localeOf` falls back to `Accept-Language`.
            locale  = request.queryParam("locale").flatMap(Locale.fromString).getOrElse(RouteSupport.localeOf(request))
            // `Tokens`, not `Random.nextUUID`: ZIO's live `Random` is `scala.util.Random`, a 48-bit LCG whose
            // state is recoverable from a couple of sampled outputs. This nonce is the only thing standing
            // between the callback and a cross-site request, since it is the one route that cannot require
            // the CSRF header — so it has to come from a `SecureRandom` like every other bearer string here.
            nonce  <- Tokens.urlSafe()
            url    <- ZIO
                        .fromEither(URL.decode(client.authorizationUrl(nonce)))
                        .tapErrorCause(cause =>
                          ZIO.logErrorCause(s"Could not build the ${client.provider.wire} authorization URL", cause)
                        )
                        .mapError(_ => {
                          errorResponse(
                            Status.InternalServerError,
                            MessageRef(MessageKeys.oauthUnavailable),
                            "Sign-in is unavailable",
                          )
                        })
          } yield {
            val cookieValue = s"$nonce|${OAuthIntent.wire(intent)}|${locale.code}"
            Response.redirect(url).addCookie(oauthStateCookie(cookieValue, cfg.session.cookieSecure, 10.minutes))
          }
        }
      }
  }

  private val oauthCallbackRoute = {
    Method.GET / "api" / "auth" / string("provider") / "callback" ->
      handler { (segment: String, request: Request) =>
        withContext { (cfg: AppConfig) =>
          for {
            client      <- resolveClient(segment)
            // The cookie is read before anything else can fail, because every failure below needs the intent
            // to know which page to land the user on.
            cookieValue <- ZIO
                             .fromOption(request.cookie(oauthStateCookieName).map(_.content))
                             .orElseFail(oauthErrorRedirect(cfg, OAuthIntent.Login, Locale.default, "missing_state"))
            cookieParts  = cookieValue.split('|')
            intent       = cookieParts.lift(1).flatMap(OAuthIntent.parse).getOrElse(OAuthIntent.Login)
            // A cookie written by an older build has no third part; falling back to the default just
            // means an English landing page for a flow that was already in flight across a deploy.
            locale       = cookieParts.lift(2).flatMap(Locale.fromString).getOrElse(Locale.default)
            nonce        = cookieParts.headOption.getOrElse("")
            state       <- ZIO
                             .fromOption(request.queryParam("state"))
                             .orElseFail(oauthErrorRedirect(cfg, intent, locale, "missing_state"))
            _           <- ZIO.unless(nonce.nonEmpty && state == nonce)(
                             ZIO.fail(oauthErrorRedirect(cfg, intent, locale, "state_mismatch"))
                           )
            code        <-
              ZIO
                .fromOption(request.queryParam("code"))
                .orElseFail(oauthErrorRedirect(cfg, intent, locale, "missing_code"))
            identity    <-
              client
                .exchangeAndVerify(code)
                .tapErrorCause(cause =>
                  ZIO.logErrorCause(s"${client.provider.wire} code exchange or id_token verification failed", cause)
                )
                .mapError(_ => oauthErrorRedirect(cfg, intent, locale, "failed"))
            response    <-
              intent match {
                case OAuthIntent.Login =>
                  AuthService
                    .loginWithOAuth(identity, locale)
                    .mapBoth(
                      {
                        // The one failure the user can actually act on, and the entire reason the settings
                        // page exists: their email is taken by an account that has never linked this provider.
                        case AuthFailure.OAuthAccountExists(_) =>
                          oauthErrorRedirect(cfg, intent, locale, "account_exists")
                        case _                                 =>
                          oauthErrorRedirect(cfg, intent, locale, "failed")
                      },
                      { case (_, sessionId) =>
                        redirectResponse(s"${cfg.app.publicBaseUrl}${locale.urlPrefix}/").addCookie(
                          SessionAuth.buildSessionCookie(sessionId, cfg.session.cookieSecure)
                        )
                      },
                    )
                case OAuthIntent.Link  =>
                  for {
                    // The `authenticated` aspect cannot cover this route: its failure is a 401 body, and every
                    // exit from a top-level navigation has to be a redirect. So the session is resolved here.
                    sessionId <- ZIO
                                   .fromOption(SessionAuth.sessionIdFrom(request))
                                   .orElseFail(oauthErrorRedirect(cfg, intent, locale, "link_requires_session"))
                    user      <- AuthService
                                   .currentUser(sessionId)
                                   .someOrFail(oauthErrorRedirect(cfg, intent, locale, "link_requires_session"))
                    _         <- AuthService
                                   .linkOAuth(user.id, identity)
                                   .mapError {
                                     case AuthFailure.OAuthAlreadyLinked =>
                                       oauthErrorRedirect(cfg, intent, locale, "already_linked")
                                     case _                              =>
                                       oauthErrorRedirect(cfg, intent, locale, "failed")
                                   }
                  } yield redirectResponse(
                    s"${cfg.app.publicBaseUrl}${locale.urlPrefix}/settings?linked=${identity.provider.wire}"
                  )
              }
          } yield {
            response.addCookie(oauthStateCookie("", cfg.session.cookieSecure, Duration.Zero))
          }
        }
      }
  }

  /** Signing up, signing in and signing out are reachable without a session by definition; `/api/me` and the theme
    * preference are not. Splitting the sets is what lets the `authenticated` aspect cover exactly the latter and the
    * `requestContext` aspect exactly the former, with the CSRF check spanning everything.
    */
  private val anonymousRoutes = {
    Routes(
      signupRoute,
      loginRoute,
      logoutRoute,
      verifyEmailRoute,
      resendVerificationRoute,
      // Both mint a session for somebody who has none: one for a brand-new guest, one for a guest arriving on a
      // second machine with a transfer code. Both are rate-limited on the client address, which is what they need
      // `requestContext` for.
      createGuestRoute,
      claimGuestRoute,
    ) @@ RouteSupport.requestContext
  }

  /** Reachable without a session and needing none of the context the other anonymous routes do — the sign-in form reads
    * it before anyone has signed in.
    */
  private val publicRoutes = {
    Routes(providersRoute)
  }

  private val sessionRoutes = {
    Routes(meRoute, updateThemeRoute, updateLocaleRoute, identitiesRoute, unlinkIdentityRoute, setPasswordRoute) @@
      RouteSupport.authenticated
  }

  /** The two guest routes that need a session *and* the request context — the upgrade writes a verification email, so
    * it needs the language the caller is reading. Attached to the `Routes` value rather than to the handlers, like
    * everywhere else.
    */
  private val guestSessionRoutes = {
    Routes(guestCodeRoute, upgradeGuestRoute) @@ RouteSupport.authenticated @@ RouteSupport.requestContext
  }

  private val oauthRoutes = {
    Routes(oauthStartRoute, oauthCallbackRoute)
  }

  val routes: Routes[AuthService & OAuthClients & AppConfig, Response] = {
    (anonymousRoutes ++ publicRoutes ++ sessionRoutes ++ guestSessionRoutes ++ oauthRoutes) @@ RouteSupport.csrf
  }
}
