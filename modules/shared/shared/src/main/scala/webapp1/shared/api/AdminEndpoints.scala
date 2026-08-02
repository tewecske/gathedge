package webapp1.shared.api

import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateUserRequest, UpdateUserRequest}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failingWith, failure}
import ApiSchemas.given

/** Administrator user management.
  *
  * The admin check is an aspect on the `Routes` value, not part of any description here, so these look like every other
  * resource; what makes them admin-only lives in `AdminRoutes`. It is the reason every endpoint below declares both 401
  * and 403 including the GETs: `adminOnly` answers 401 with no session and 403 with a non-administrator one, so unlike
  * elsewhere the 403 is not the CSRF aspect and does not follow the method.
  */
object AdminEndpoints {

  private val userId = PathCodec.long("id")

  /** `AdminService.listUsers` is a `UIO`, so only the aspect and a defect can fail this. */
  val listUsers = {
    Endpoint(Method.GET / "api" / "admin" / "users")
      .out[List[User]]
      .failingWith(failure.unauthorized, failure.forbidden, failure.internalError)
  }

  val getUser = {
    Endpoint(Method.GET / "api" / "admin" / "users" / userId)
      .out[User]
      .failingWith(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val createUser = {
    Endpoint(Method.POST / "api" / "admin" / "users")
      .in[CreateUserRequest]
      .out[User](Status.Created)
      .failingWith(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val updateUser = {
    Endpoint(Method.PUT / "api" / "admin" / "users" / userId)
      .in[UpdateUserRequest]
      .out[User]
      .failingWith(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  /** The success response is described as a bare status, not as `.out[Unit](Status.NoContent)`.
    *
    * Both put a 204 with an empty body on the wire, but `out[Unit]` also installs a *body* codec, and decoding it needs
    * to know the body is empty. A 204 must not carry `Content-Length` (RFC 9110 §8.6), and zio-http's Scala.js body
    * (`FetchBodyInternal.isEmpty`) reports empty only when that header says `0` — so a browser client built from this
    * description fails every delete with "Non-empty body cannot be decoded as Unit". A status-only codec has no body to
    * decode and sidesteps it. Every other 204 in this package is described the same way for the same reason.
    */
  val deleteUser = {
    Endpoint(Method.DELETE / "api" / "admin" / "users" / userId)
      .outCodec(HttpCodec.status(Status.NoContent))
      .failingWith(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(listUsers, getUser, createUser, updateUser, deleteUser)
}
