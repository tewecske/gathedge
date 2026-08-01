package webapp1.backend.http

import webapp1.backend.security.SessionAuth
import webapp1.backend.service.AuthService
import webapp1.shared.domain.User
import zio.*
import zio.http.*

import JsonSupport.*

/** Auth/CSRF helpers shared by every route file (Auth/Todo/Group/...). */
object RouteSupport {

  private val securityLog = org.slf4j.LoggerFactory.getLogger("security")

  // JSON API + this required header blocks cross-site form/simple-fetch CSRF.
  def csrfCheck(request: Request): ZIO[Any, Response, Unit] = {
    ZIO
      .unless(SessionAuth.hasValidCsrfHeader(request)) {
        ZIO.fail(errorResponse(Status.Forbidden, "Missing required header"))
      }
      .unit
  }

  def authenticatedUser(request: Request): ZIO[AuthService, Response, User] = {
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

  /** Any admin-only page/endpoint denies a signed-in non-admin with a message explaining they're signed in but lack
    * admin rights (summary.md).
    */
  def requireAdmin(request: Request): ZIO[AuthService, Response, User] = {
    authenticatedUser(request).flatMap { user =>
      if (user.isAdmin)
        ZIO.succeed(user)
      else {
        ZIO.succeed(
          securityLog.warn(s"Admin-only route denied for '${user.email}': ${request.method} ${request.path}")
        ) *> ZIO.fail(errorResponse(Status.Forbidden, "You are signed in but do not have administrator rights"))
      }
    }
  }
}
