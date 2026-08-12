package gathedge.backend.http

import gathedge.backend.security.SessionAuth
import zio.*
import zio.http.*

/** Drives real `Routes` values in-process, through the same `RouteSupport.handleFailures` wrapper `Main` installs, so a
  * spec sees exactly the response a client would: `Routes` now carry `Response` in the error channel, and without that
  * wrapper an error response would surface as a failed effect rather than a `Response` to assert on.
  *
  * This is zio-http's "direct route testing" — no socket, no server. `AuthFlowSpec` covers the over-the-wire path.
  */
object RouteRunner {

  def runRoutes[Env](routes: Routes[Env, Response], request: Request): ZIO[Env & Scope, Nothing, Response] = {
    RouteSupport.handleFailures(routes).runZIO(request)
  }

  def withCsrf(request: Request): Request = {
    request.addHeader(SessionAuth.csrfHeaderName, SessionAuth.csrfHeaderValue)
  }

  def withSession(request: Request, sessionId: String): Request = {
    request.addCookie(Cookie.Request(SessionAuth.cookieName, sessionId))
  }

  /** A GET whose URL carries a query string.
    *
    * `Request.get("/path?a=b")` does **not** parse one: the string becomes the path verbatim, so the request matches no
    * route and the response is `handleFailures`' 404 rather than anything the endpoint had a say in. That looks exactly
    * like a broken endpoint and is not, which is why this exists instead of `URL.decode` at each call site.
    */
  def getWithQuery(url: String): Request = {
    Request.get(URL.decode(url).getOrElse(throw new IllegalArgumentException(s"not a URL: $url")))
  }

  /** The service failures in test fixtures are all "the fixture couldn't be set up", never the thing under test. */
  def orDieWithFailure[R, E, A](effect: ZIO[R, E, A]): ZIO[R, Nothing, A] = {
    effect.orDieWith(failure => new RuntimeException(s"test setup failed: $failure"))
  }
}
