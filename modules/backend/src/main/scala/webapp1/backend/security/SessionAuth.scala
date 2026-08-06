package webapp1.backend.security

import zio.*
import zio.http.*

/** Cookie mechanics + CSRF header check. Session lookup against the DB lives in `AuthService` (it needs the
  * repositories); this object only knows about HTTP-level concerns.
  */
object SessionAuth {
  val cookieName                = "session"
  val sessionDuration: Duration = 7.days

  // JSON-only API + this required header blocks cross-site form/simple-fetch CSRF
  // without needing a separate token scheme (cross-site requests can't set custom
  // headers without a CORS preflight, which we don't allow).
  val csrfHeaderName  = "X-Requested-With"
  val csrfHeaderValue = "XMLHttpRequest"

  def sessionIdFrom(request: Request): Option[String] = {
    request.cookie(cookieName).map(_.content)
  }

  def hasValidCsrfHeader(request: Request): Boolean = {
    request.rawHeader(csrfHeaderName).contains(csrfHeaderValue)
  }

  def buildSessionCookie(sessionId: String, secure: Boolean): Cookie.Response = {
    Cookie.Response(
      name = cookieName,
      content = sessionId,
      path = Some(Path.root),
      isHttpOnly = true,
      isSecure = secure,
      sameSite = Some(Cookie.SameSite.Lax),
      maxAge = Some(sessionDuration),
    )
  }

  def expiredSessionCookie(secure: Boolean): Cookie.Response = {
    Cookie.Response(
      name = cookieName,
      content = "",
      path = Some(Path.root),
      isHttpOnly = true,
      isSecure = secure,
      sameSite = Some(Cookie.SameSite.Lax),
      maxAge = Some(Duration.Zero),
    )
  }
}
