package webapp1.shared.api

import webapp1.shared.dto.{AuthResponse, LoginRequest, SignupRequest, UpdateThemeRequest}
import zio.http.{Method, Status}
import zio.http.codec.HttpCodec
import zio.http.endpoint.Endpoint

import ApiEndpoint.{sessionCookie, withErrors}
import ApiSchemas.given

/** Sign-up, sign-in, sign-out and the signed-in user's own record.
  *
  * The two Google OAuth routes are *not* here. They are top-level browser navigations whose success and failure are
  * both redirects rather than bodies, so they stay on the imperative DSL in `AuthRoutes` — see the note there.
  */
object AuthEndpoints {

  val signup = {
    withErrors(
      Endpoint(Method.POST / "api" / "auth" / "signup")
        .in[SignupRequest]
        .out[AuthResponse](Status.Created)
        .outHeader(sessionCookie)
    )
  }

  val login = {
    withErrors(
      Endpoint(Method.POST / "api" / "auth" / "login").in[LoginRequest].out[AuthResponse].outHeader(sessionCookie)
    )
  }

  /** Answers 204 whether or not there was a session to end, and always sends the already-expired cookie back.
    *
    * The success is described as a bare status rather than `.out[Unit](Status.NoContent)` for the reason recorded on
    * [[AdminEndpoints.deleteUser]]: `out[Unit]` installs a body codec that needs `Content-Length: 0` to recognise an
    * empty body, which a 204 must not send.
    */
  val logout = {
    withErrors(
      Endpoint(Method.POST / "api" / "auth" / "logout")
        .outCodec(HttpCodec.status(Status.NoContent))
        .outHeader(sessionCookie)
    )
  }

  val me = {
    withErrors(Endpoint(Method.GET / "api" / "me").out[AuthResponse])
  }

  val updateTheme = {
    withErrors(Endpoint(Method.PUT / "api" / "me" / "theme").in[UpdateThemeRequest].out[AuthResponse])
  }

  /** For `DocsRoutes`, which needs every description as one heterogeneous collection to generate the OpenAPI document
    * from. Nothing else should reach for it — the implementations and the client name the endpoints individually.
    */
  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(signup, login, logout, me, updateTheme)
}
