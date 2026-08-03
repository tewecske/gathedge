package webapp1.shared.api

import webapp1.shared.dto.{AuthResponse, LoginRequest, SignupRequest, UpdateThemeRequest}
import zio.http.{Method, Status}
import zio.http.codec.HttpCodec
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure, sessionCookie, withCodecError}
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
      .withCodecError
      .out[AuthResponse](Status.Created)
      .outHeader(sessionCookie)
      .outErrors(failure.badRequest, failure.unauthorized, failure.conflict, failure.tooManyRequests)
  }

  val login = {
    Endpoint(Method.POST / "api" / "auth" / "login")
      .in[LoginRequest]
      .withCodecError
      .out[AuthResponse]
      .outHeader(sessionCookie)
      .outErrors(failure.badRequest, failure.unauthorized, failure.conflict, failure.tooManyRequests)
  }

  /** Answers 204 whether or not there was a session to end, and always sends the already-expired cookie back.
    *
    * The success is described as a bare status rather than `.out[Unit](Status.NoContent)` for the reason recorded on
    * [[AdminEndpoints.deleteUser]]: `out[Unit]` installs a body codec that needs `Content-Length: 0` to recognise an
    * empty body, which a 204 must not send.
    *
    * Logging out without a session is a no-op rather than a failure, so the handler cannot fail — and the two statuses
    * that could still come back (the CSRF aspect's 403, a defect's 500) are not described here, for the reason recorded
    * on [[ApiEndpoint.failure]]. This is consequently the one endpoint in the API that declares no failure at all.
    */
  val logout = {
    Endpoint(Method.POST / "api" / "auth" / "logout")
      .outCodec(HttpCodec.status(Status.NoContent))
      .outHeader(sessionCookie)
  }

  /** Reads back the `User` the `authenticated` aspect already resolved, so that aspect's 401 is the only failure a
    * caller can act on.
    */
  val me = {
    Endpoint(Method.GET / "api" / "me").out[AuthResponse].outFailure(failure.unauthorized)
  }

  /** `AuthService.updateTheme` is `.orDie`'d in the handler — a failure there is a bug or a dead database, not
    * something the caller can act on — so the declared failures are the `authenticated` aspect's 401 and a 400 that no
    * handler raises: it is reachable only by the request codec rejecting the body, which `ApiEndpoint.withCodecError`
    * answers. Declaring it is what makes that a value the client can act on rather than a defect, since a status a
    * description omits is not decodable at all.
    */
  val updateTheme = {
    Endpoint(Method.PUT / "api" / "me" / "theme")
      .in[UpdateThemeRequest]
      .withCodecError
      .out[AuthResponse]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** For `DocsRoutes`, which needs every description as one heterogeneous collection to generate the OpenAPI document
    * from. Nothing else should reach for it — the implementations and the client name the endpoints individually.
    */
  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(signup, login, logout, me, updateTheme)
}
