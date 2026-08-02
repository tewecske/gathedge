package webapp1.shared.api

import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateUserRequest, UpdateUserRequest}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.withErrors
import ApiSchemas.given

/** Administrator user management.
  *
  * The admin check is an aspect on the `Routes` value, not part of any description here, so these look like every other
  * resource; what makes them admin-only lives in `AdminRoutes`.
  */
object AdminEndpoints {

  private val userId = PathCodec.long("id")

  val listUsers = {
    withErrors(Endpoint(Method.GET / "api" / "admin" / "users").out[List[User]])
  }

  val getUser = {
    withErrors(Endpoint(Method.GET / "api" / "admin" / "users" / userId).out[User])
  }

  val createUser = {
    withErrors(Endpoint(Method.POST / "api" / "admin" / "users").in[CreateUserRequest].out[User](Status.Created))
  }

  val updateUser = {
    withErrors(Endpoint(Method.PUT / "api" / "admin" / "users" / userId).in[UpdateUserRequest].out[User])
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
    withErrors(
      Endpoint(Method.DELETE / "api" / "admin" / "users" / userId).outCodec(HttpCodec.status(Status.NoContent))
    )
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(listUsers, getUser, createUser, updateUser, deleteUser)
}
