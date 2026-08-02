package webapp1.shared.api

import webapp1.shared.dto.{AuthResponse, LoginRequest, SignupRequest, UpdateThemeRequest}
import zio.http.{Method, Status}
import zio.http.codec.HttpCodec
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failingWith, failure, sessionCookie}
import ApiSchemas.given

/** Sign-up, sign-in, sign-out and the signed-in user's own record.
  *
  * The two Google OAuth routes are *not* here. They are top-level browser navigations whose success and failure are
  * both redirects rather than bodies, so they stay on the imperative DSL in `AuthRoutes` — see the note there.
  *
  * These are the only endpoints in the API that declare 429: the rate limiter lives in `AuthService` and only signup
  * and login go through it.
  */
object AuthEndpoints {

  val signup = {
    Endpoint(Method.POST / "api" / "auth" / "signup")
      .in[SignupRequest]
      .out[AuthResponse](Status.Created)
      .outHeader(sessionCookie)
      .failingWith(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.conflict,
        failure.tooManyRequests,
        failure.internalError,
      )
  }

  val login = {
    Endpoint(Method.POST / "api" / "auth" / "login")
      .in[LoginRequest]
      .out[AuthResponse]
      .outHeader(sessionCookie)
      .failingWith(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.conflict,
        failure.tooManyRequests,
        failure.internalError,
      )
  }

  /** Answers 204 whether or not there was a session to end, and always sends the already-expired cookie back.
    *
    * The success is described as a bare status rather than `.out[Unit](Status.NoContent)` for the reason recorded on
    * [[AdminEndpoints.deleteUser]]: `out[Unit]` installs a body codec that needs `Content-Length: 0` to recognise an
    * empty body, which a 204 must not send.
    *
    * Logging out without a session is a no-op rather than a failure, so the handler cannot fail: 403 is the CSRF aspect
    * and 500 is a defect.
    */
  val logout = {
    Endpoint(Method.POST / "api" / "auth" / "logout")
      .outCodec(HttpCodec.status(Status.NoContent))
      .outHeader(sessionCookie)
      .failingWith(failure.forbidden, failure.internalError)
  }

  /** Reads back the `User` the `authenticated` aspect already resolved, so that aspect's 401 is the only failure a
    * caller can act on. A GET is outside the CSRF check's method scope, hence no 403.
    */
  val me = {
    Endpoint(Method.GET / "api" / "me").out[AuthResponse].failingWith(failure.unauthorized, failure.internalError)
  }

  /** `AuthService.updateTheme` is `.orDie`'d in the handler — a failure there is a bug or a dead database, not
    * something the caller can act on — so the declared failures are the two aspects and the generic 500.
    */
  val updateTheme = {
    Endpoint(Method.PUT / "api" / "me" / "theme")
      .in[UpdateThemeRequest]
      .out[AuthResponse]
      .failingWith(failure.unauthorized, failure.forbidden, failure.internalError)
  }

  /** For `DocsRoutes`, which needs every description as one heterogeneous collection to generate the OpenAPI document
    * from. Nothing else should reach for it — the implementations and the client name the endpoints individually.
    */
  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(signup, login, logout, me, updateTheme)
}
