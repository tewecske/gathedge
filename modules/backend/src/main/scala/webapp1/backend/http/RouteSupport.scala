package webapp1.backend.http

import webapp1.backend.config.AppConfig
import webapp1.backend.security.{SecurityLog, SessionAuth}
import webapp1.backend.service.AuthService
import webapp1.shared.domain.{Locale, User}
import webapp1.shared.i18n.{MessageKeys, MessageRef}
import zio.*
import zio.http.*

import java.time.Instant

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
              .as(
                errorResponse(
                  Status.InternalServerError,
                  MessageRef(MessageKeys.internalError),
                  "Internal server error",
                )
              )
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
    handled.notFound =
      Handler.fromFunction[Request](_ => errorResponse(Status.NotFound, MessageRef(MessageKeys.notFound), "Not found"))
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

  /** The request log's version of a URL: the path, and never the query string.
    *
    * One credential in this API travels inside a URL rather than a body, because it is followed as a link rather than
    * sent by script: the OAuth authorization code arrives as `?code=` on the callback. `Middleware.requestLogging`
    * logged the whole URL, so it was written to `logs/backend.log` and to `docker logs` on every request — while
    * `QuillRepository.logged` was carefully keeping the same class of secret out of the lines one layer down.
    *
    * The query string is dropped wholesale rather than filtered. The only other endpoints that use one are the admin
    * list filters, whose values are worth less than an allowlist that some later endpoint forgets to join — the user
    * search term in particular is a fragment of somebody's address.
    *
    * '''A credential that arrives as a path segment needs more than this.''' If you add an endpoint whose path carries
    * a token — `/api/invitations/{token}` is the shape this project used to have — replace that segment here before the
    * path is joined, and pin it in `RouteSupportSpec`.
    */
  private[http] def loggableUrl(request: Request): String = {
    request.path.segments.mkString("/", "/", "")
  }

  /** One log line per request, replacing `Middleware.requestLogging()`.
    *
    * It exists only because that middleware offers no hook to rewrite the URL — its one relevant parameter maps a
    * status to a log level — and the URL is exactly what had to change (see [[loggableUrl]]). Everything else is
    * deliberately identical to the library's version, down to the four annotation keys and their value shapes
    * (`status.text`, not the code), so `logback.xml`'s `%kvp` rendering and the comment there explaining why it is
    * needed both stay true, and so does anything already grepping these lines.
    *
    * Built with `interceptHandlerStateful` — the same combinator the library uses — because that is what carries the
    * start instant from before the handler to after it. Timing it by calling the inner handler directly does not
    * typecheck: `Handler.apply` returns `ZIO[Env & Scope, ...]`, and the `Scope` belongs to the response body, which
    * may still be streaming when this line is written.
    */
  val requestLogging: HandlerAspect[Any, Unit] = {
    HandlerAspect.interceptHandlerStateful(
      Handler.fromFunctionZIO[Request] { (request: Request) =>
        Clock.instant.map(now => ((now, request), (request, ())))
      }
    )(
      Handler.fromFunctionZIO[((Instant, Request), Response)] { case ((start, request), response) =>
        Clock.instant.flatMap { end =>
          ZIO.logAnnotate(
            LogAnnotation("status_code", response.status.text),
            LogAnnotation("method", request.method.toString),
            LogAnnotation("url", loggableUrl(request)),
            LogAnnotation("duration_ms", java.time.Duration.between(start, end).toMillis.toString),
          )(ZIO.logInfo("Http request served").as(response))
        }
      }
    )
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
          else {
            ZIO.fail(
              errorResponse(Status.Forbidden, MessageRef(MessageKeys.missingCsrfHeader), "Missing required header")
            )
          }
        }
      )
      .when(request => !safeMethods.contains(request.method))
  }

  /** The parts of the raw `Request` that the described endpoints need but deliberately do not describe.
    *
    * A handler implemented against an `Endpoint` receives its declared inputs and nothing else — there is no `Request`
    * to reach into. For the first two that is the right outcome and not a limitation to work around: neither belongs in
    * a description, because neither can be supplied by a client. The peer address is not a header at all, and the
    * session cookie is a forbidden request header that a browser sets itself and page script cannot. Declaring either
    * as an input would produce a client that has to fabricate a value it does not have.
    *
    * So they arrive the same way the authenticated `User` does: resolved once by an aspect and handed to the handler
    * through the environment.
    *
    * [[locale]] is the odd one out and is here for a different reason: it *could* be described, as a header codec on
    * every endpoint that needs it. It is not, because it would have to go on the three that send mail and then be kept
    * in step by hand forever, and because it is a property of the request rather than of any one operation — the same
    * argument that puts the client address here. See [[localeOf]] for where the value comes from.
    */
  final case class RequestContext(clientIp: Option[String], sessionId: Option[String], locale: Locale)

  private val forwardedForHeader = "X-Forwarded-For"

  /** Cheap sanity check on a value taken out of `X-Forwarded-For`, so a malformed hop cannot turn into an unbounded
    * rate-limit key. Deliberately not a full address parser — anything shaped like one is fine here.
    */
  private val addressPattern = "^[0-9a-fA-F.:%]{1,64}$".r

  /** Which address to hold this request against, given how many proxies are known to stand in front of the server.
    *
    * With `trustedProxyHops == 0` the header is ignored outright and the socket peer is the answer, because a client
    * talking to us directly can put anything it likes in `X-Forwarded-For` and would otherwise be choosing its own
    * rate-limit identity — a fresh budget per request.
    *
    * Above zero the header is read '''right to left''', which is the only direction that is not forgeable: each proxy
    * *appends* the address it received the request from, so the last entry was written by the proxy nearest us and the
    * entry `trustedProxyHops` from the right is the address seen by the outermost proxy we trust. Anything further left
    * was supplied by the caller and is ignored, so a client sending `X-Forwarded-For: 10.0.0.1` merely has that value
    * pushed left of the one nginx appends.
    *
    * A header that is missing, shorter than the claimed hop count, or carrying something that is not address-shaped
    * falls back to the socket peer. That is the safe direction: it is the value that cannot be forged, and the cost of
    * using it is a shared budget rather than a bypassed one.
    *
    * Pure, and separate from the aspect below, because every one of those branches is worth a test.
    */
  def clientAddress(request: Request, trustedProxyHops: Int): Option[String] = {
    val peer = request.remoteAddress.map(_.getHostAddress)
    if (trustedProxyHops <= 0) {
      peer
    } else {
      val forwarded = {
        request
          .rawHeader(forwardedForHeader)
          .toList
          .flatMap(_.split(',').toList)
          .map(_.trim)
          .filter(entry => entry.nonEmpty && addressPattern.matches(entry))
      }
      // One hop means the last entry, two means the one before it, and so on.
      forwarded.lift(forwarded.length - trustedProxyHops).orElse(peer)
    }
  }

  private val localeHeader = "X-Locale"

  /** Which language this request is being made in.
    *
    * `X-Locale` is what the generated client sends on every call, carrying the locale from the URL prefix the SPA is
    * running under — so it is the language the person is actually looking at, which is the one an email triggered by
    * this request should be written in.
    *
    * `Accept-Language` is the fallback for anything that is not this SPA: a `curl`, a future mobile client, a link
    * checker. It is only ever a hint here, never an override, because a browser's language list has nothing to do with
    * which prefix the user chose to open.
    *
    * Pure and separate from the aspect for the same reason [[clientAddress]] is: each branch is worth a test.
    */
  def localeOf(request: Request): Locale = {
    request
      .rawHeader(localeHeader)
      .flatMap(Locale.fromString)
      .orElse(request.rawHeader("Accept-Language").flatMap(Locale.fromAcceptLanguage))
      .getOrElse(Locale.default)
  }

  /** Supplies [[RequestContext]]. Never fails — the optional fields are optional, and "no session" is a legitimate
    * state for the endpoints that ask for this (signing up, signing in, and signing out with an already-dead cookie).
    *
    * It needs `AppConfig` only for [[clientAddress]]'s hop count. That is worth the environment requirement: the
    * address this resolves is what the rate limiter keys on, and getting it wrong is not a degraded limit but a
    * deployment-wide outage — behind the shipped nginx, every request shared the proxy's address, so five failed
    * sign-ins from anyone at all blocked sign-in, sign-up and verification resends for every account, for as long as
    * the failures kept arriving.
    */
  val requestContext: HandlerAspect[AppConfig, RequestContext] = {
    HandlerAspect.interceptIncomingHandler(
      Handler.fromFunctionZIO[Request] { (request: Request) =>
        ZIO.serviceWith[AppConfig] { config =>
          (
            request,
            RequestContext(
              clientAddress(request, config.app.trustedProxyHops),
              SessionAuth.sessionIdFrom(request),
              localeOf(request),
            ),
          )
        }
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
      user      <- ZIO
                     .fromOption(maybeUser)
                     .orElseFail(
                       errorResponse(Status.Unauthorized, MessageRef(MessageKeys.notAuthenticated), "Not authenticated")
                     )
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
              ZIO.fail(
                errorResponse(
                  Status.Forbidden,
                  MessageRef(MessageKeys.notAdministrator),
                  "You are signed in but do not have administrator rights",
                )
              )
          }
        }
      }
    )
  }
}
