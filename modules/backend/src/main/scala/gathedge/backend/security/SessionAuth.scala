package gathedge.backend.security

import zio.*
import zio.http.*

/** Cookie mechanics + CSRF header check. Session lookup against the DB lives in `AuthService` (it needs the
  * repositories); this object only knows about HTTP-level concerns.
  */
object SessionAuth {
  val cookieName                = "session"
  val sessionDuration: Duration = 7.days

  /** How long a guest's session lasts. Far longer than a signed-in one, and deliberately so: a guest has no address and
    * no password, so an expired cookie is not an inconvenience but the end of their vocabulary. The way out of that
    * asymmetry is the transfer code, not a shorter window.
    */
  val guestSessionDuration: Duration = 365.days

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

  /** `duration` has to match the expiry the session row was written with, or the browser and the database disagree
    * about when the session ends — for a guest that is the difference between a year and a week.
    */
  def buildSessionCookie(
    sessionId: String,
    secure: Boolean,
    duration: Duration = sessionDuration,
  ): Cookie.Response = {
    Cookie.Response(
      name = cookieName,
      content = sessionId,
      path = Some(Path.root),
      isHttpOnly = true,
      isSecure = secure,
      sameSite = Some(Cookie.SameSite.Lax),
      maxAge = Some(duration),
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
