package webapp1.backend.http

import webapp1.backend.security.{SecurityLog, SessionAuth}
import webapp1.backend.service.AuthService
import webapp1.shared.domain.User
import zio.*
import zio.http.*

import JsonSupport.*

/** Auth/CSRF helpers shared by every route file (Auth/Todo/Group/...). */
object RouteSupport {

  /** Turns defects — every `.orDie` in the services ends up here — into a logged cause plus a generic JSON 500.
    *
    * Without it zio-http answers an un-sandboxed defect with
    * `Response.internalServerError(FiberFailure(cause).getMessage)`, i.e. the entire cause chain and stack trace as the
    * response body, and logs nothing at all: zio-http only logs unhandled causes from inside `Route#sandbox`, which a
    * `Routes[Env, Nothing]` never goes through.
    */
  def handleDefects[Env](routes: Routes[Env, Nothing]): Routes[Env, Nothing] = {
    routes.handleErrorRequestCauseZIO { (request: Request, cause: Cause[Nothing]) =>
      ZIO
        .logErrorCause(s"Unhandled failure serving ${request.method} ${request.path}", cause)
        .unless(cause.isInterruptedOnly)
        .as(errorResponse(Status.InternalServerError, "Internal server error"))
    }
  }

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
        SecurityLog.warn(s"Admin-only route denied for '${user.email}': ${request.method} ${request.path}") *>
          ZIO.fail(errorResponse(Status.Forbidden, "You are signed in but do not have administrator rights"))
      }
    }
  }
}
