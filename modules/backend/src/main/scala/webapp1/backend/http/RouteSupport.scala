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
  * the handler through the environment, so a protected handler just asks for it with `withContext` and cannot forget to
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
    *
    * Both halves log. The 500 body is deliberately opaque — a client is told nothing beyond "internal server error" —
    * so the log line is the only account of what happened, and it names the defect inline rather than leaving it to the
    * attached cause: the cause reaches logback as a `FiberFailure` throwable and prints as a stack trace under the
    * message, which is unreadable at a glance and unfindable with `grep`. A handled failure is a `Response` a handler
    * built on purpose and needs no attention, but a 4xx that surprises whoever is looking at the frontend is much
    * easier to place when the server says which status it sent for which path, hence the debug line.
    */
  def handleFailures[Env](routes: Routes[Env, Response]): Routes[Env, Nothing] = {
    val handled = {
      routes.handleErrorRequestCauseZIO { (request: Request, cause: Cause[Response]) =>
        cause.failureOption match {
          case Some(response) =>
            ZIO
              .logDebug(s"${request.method} ${request.path} -> ${response.status.code}")
              .when(response.status.code >= 400)
              .as(response)
          case None           =>
            ZIO
              .logErrorCause(s"Unhandled failure serving ${request.method} ${request.path}: ${describe(cause)}", cause)
              .unless(cause.isInterruptedOnly)
              .as(errorResponse(Status.InternalServerError, "Internal server error"))
        }
      }
    }
    // A request that matches no route never reaches a handler, so the wrapper above cannot see it: zio-http answers it
    // from `Routes.notFound`, whose default is `Response.error(NotFound, path)` — an HTML-escaped echo of the requested
    // path, with no JSON body and no content type.
    //
    // This 404 is not the one endpoints describe. `ApiFailure.NotFound` means a resource the request named does not
    // exist; this one means the path is not part of the API at all, so there is no operation to document it on and no
    // generated client decodes it as a value — the client's URLs come from the descriptions, so it can only arrive
    // here across a version skew between a deployed frontend and an older backend. That narrow case is still worth
    // answering in the same shape as everything else, and echoing the requested path back is worth not doing, which is
    // why the default is replaced rather than left alone.
    //
    // `notFound` is a mutable field on `Routes` typed `Handler[Any, Nothing, Request, Response]`, so assigning it
    // cannot affect this method's `Routes[Env, Nothing]` result — that comes from `handleErrorRequestCauseZIO` above.
    // `handled` is a fresh value built one line up, and the combinators that follow (`@@ requestLogging` in `Main`)
    // carry the replacement along.
    handled.notFound = Handler.fromFunction[Request](_ => errorResponse(Status.NotFound, "Not found"))
    handled
  }

  /** The one-line form of a defect, for the head of the log entry.
    *
    * The root cause is what actually names the problem — a `.orDie`'d repository call arrives wrapped, and it is the
    * innermost `SQLException` that says which constraint or column was at fault. Newlines are flattened because several
    * drivers put detail on a second line (Postgres' `Detail: Key (id)=(28) is still referenced from ...`), which would
    * otherwise split the entry and hide the useful half from a `grep`.
    */
  private def describe(cause: Cause[Any]): String = {
    cause.defects.headOption match {
      case None         =>
        if (cause.isInterruptedOnly)
          "interrupted"
        else
          "no defect"
      case Some(defect) =>
        val root = rootCause(defect)
        val head = s"${defect.getClass.getName}: ${oneLine(defect.getMessage)}"
        if (root eq defect)
          head
        else
          s"$head [caused by ${root.getClass.getName}: ${oneLine(root.getMessage)}]"
    }
  }

  /** Bounded rather than recursive to the end: a `getCause` chain can be self-referential, and this runs on the way to
    * answering a request.
    */
  private def rootCause(throwable: Throwable): Throwable = {
    var current = throwable
    var depth   = 0
    while (current.getCause != null && (current.getCause ne current) && depth < 10) {
      current = current.getCause
      depth += 1
    }
    current
  }

  private def oneLine(message: String): String = {
    if (message == null)
      "(no message)"
    else
      message.replaceAll("\\s*\\R\\s*", " ").trim
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
      maybeUser <-
        SessionAuth.sessionIdFrom(request) match {
          case None      =>
            ZIO.succeed(None)
          case Some(sid) =>
            AuthService.currentUser(sid)
        }
      user      <- ZIO.fromOption(maybeUser).orElseFail(errorResponse(Status.Unauthorized, "Not authenticated"))
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
