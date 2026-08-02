package webapp1.backend.http

import webapp1.backend.security.{SecurityLog, SessionAuth}
import webapp1.backend.service.AuthService
import webapp1.shared.domain.User
import zio.*
import zio.http.*

import JsonSupport.*

/** The cross-cutting HTTP concerns, as `HandlerAspect`s rather than calls repeated at the top of every handler.
  *
  * An aspect that produces a context (`HandlerAspect[Env, User]`) resolves the value once per request and hands it to
  * the handler through the environment, so a protected handler just asks for `ZIO.service[User]` and cannot forget to
  * authenticate: the route does not compile unless some aspect supplies the `User`.
  */
object RouteSupport {

  /** Collapses a servable `Routes[Env, Response]` into `Routes[Env, Nothing]`.
    *
    * Failures are already `Response`s built by the handlers, so they pass through untouched. Defects — every `.orDie`
    * in the services ends up here — become a logged cause plus a generic JSON 500. Without the defect half, zio-http
    * answers an un-sandboxed defect with `Response.internalServerError(FiberFailure(cause).getMessage)`, i.e. the whole
    * cause chain and stack trace as the response body, and its own `sandbox` logging would produce a bare 500 with no
    * JSON body for a client that only ever parses JSON.
    */
  def handleFailures[Env](routes: Routes[Env, Response]): Routes[Env, Nothing] = {
    val handled = {
      routes.handleErrorRequestCauseZIO { (request: Request, cause: Cause[Response]) =>
        cause.failureOption match {
          case Some(response) =>
            ZIO.succeed(response)
          case None =>
            ZIO
              .logErrorCause(s"Unhandled failure serving ${request.method} ${request.path}", cause)
              .unless(cause.isInterruptedOnly)
              .as(errorResponse(Status.InternalServerError, "Internal server error"))
        }
      }
    }
    // A request that matches no route never reaches a handler, so the wrapper above cannot see it: zio-http answers it
    // from `Routes.notFound`, whose default is `Response.error(NotFound, path)` — an HTML-escaped echo of the requested
    // path, with no JSON body and no content type. That is the one response a client of this API could receive that
    // isn't the `ErrorResponse` shape everything else answers with, so it is replaced here rather than left to the
    // default. `notFound` is a mutable field on `Routes`; `handled` is a fresh value built one line above, and the
    // combinators that follow (`@@ requestLogging` in `Main`) carry the replacement along.
    handled.notFound = Handler.fromFunction[Request](_ => errorResponse(Status.NotFound, "Not found"))
    handled
  }

  /** Methods that a cross-site page can trigger without a CORS preflight, so requiring a custom header on them would
    * buy nothing and would break the OAuth callback, which arrives as a top-level browser navigation.
    */
  private val safeMethods: Set[Method] = Set(Method.GET, Method.HEAD, Method.OPTIONS)

  /** JSON API + this required header blocks cross-site form/simple-fetch CSRF. Applied to whole route sets and scoped
    * to state-changing methods, which is what the per-handler `csrfCheck` calls added up to.
    */
  val csrf: HandlerAspect[Any, Unit] = {
    HandlerAspect
      .interceptIncomingHandler(
        Handler.fromFunctionZIO[Request] { (request: Request) =>
          if (SessionAuth.hasValidCsrfHeader(request))
            ZIO.succeed((request, ()))
          else
            ZIO.fail(errorResponse(Status.Forbidden, "Missing required header"))
        }
      )
      .when(request => !safeMethods.contains(request.method))
  }

  /** The parts of the raw `Request` that the described endpoints need but deliberately do not describe.
    *
    * A handler implemented against an `Endpoint` receives its declared inputs and nothing else — there is no `Request`
    * to reach into. For these two that is the right outcome and not a limitation to work around: neither belongs in a
    * description, because neither can be supplied by a client. The peer address is not a header at all, and the session
    * cookie is a forbidden request header that a browser sets itself and page script cannot. Declaring either as an
    * input would produce a client that has to fabricate a value it does not have.
    *
    * So they arrive the same way the authenticated `User` does: resolved once by an aspect and handed to the handler
    * through the environment.
    */
  final case class RequestContext(clientIp: Option[String], sessionId: Option[String])

  /** Supplies [[RequestContext]]. Never fails — both fields are optional, and "no session" is a legitimate state for
    * the endpoints that ask for this (signing up, signing in, and signing out with an already-dead cookie).
    *
    * `clientIp` is the socket peer, deliberately not `X-Forwarded-For`: that header is attacker-controlled unless a
    * trusted-proxy list is configured, and letting it pick the rate-limit key would hand out a fresh budget per
    * request. Behind a reverse proxy this collapses to the proxy's address, leaving the per-email limit to do the work.
    */
  val requestContext: HandlerAspect[Any, RequestContext] = {
    HandlerAspect.interceptIncomingHandler(
      Handler.fromFunctionZIO[Request] { (request: Request) =>
        ZIO.succeed(
          (request, RequestContext(request.remoteAddress.map(_.getHostAddress), SessionAuth.sessionIdFrom(request)))
        )
      }
    )
  }

  private def currentUser(request: Request): ZIO[AuthService, Response, User] = {
    for {
      authService <- ZIO.service[AuthService]
      maybeUser <-
        SessionAuth.sessionIdFrom(request) match {
          case None =>
            ZIO.succeed(None)
          case Some(sid) =>
            authService.currentUser(sid)
        }
      user <- ZIO.fromOption(maybeUser).orElseFail(errorResponse(Status.Unauthorized, "Not authenticated"))
    } yield user
  }

  /** Resolves the session cookie to a `User` and provides it to the handler, or short-circuits with 401. */
  val authenticated: HandlerAspect[AuthService, User] = {
    HandlerAspect.interceptIncomingHandler(
      Handler.fromFunctionZIO[Request]((request: Request) => currentUser(request).map(user => (request, user)))
    )
  }

  /** As [[authenticated]], but any admin-only endpoint denies a signed-in non-admin with a message explaining they're
    * signed in but lack admin rights (summary.md).
    */
  val adminOnly: HandlerAspect[AuthService, User] = {
    HandlerAspect.interceptIncomingHandler(
      Handler.fromFunctionZIO[Request] { (request: Request) =>
        currentUser(request).flatMap { user =>
          if (user.isAdmin)
            ZIO.succeed((request, user))
          else {
            SecurityLog.warn(s"Admin-only route denied for '${user.email}': ${request.method} ${request.path}") *>
              ZIO.fail(errorResponse(Status.Forbidden, "You are signed in but do not have administrator rights"))
          }
        }
      }
    )
  }
}
