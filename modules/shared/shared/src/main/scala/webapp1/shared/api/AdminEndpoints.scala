package webapp1.shared.api

import webapp1.shared.domain.{Theme, User}
import webapp1.shared.dto.{CreateUserRequest, UpdateUserRequest}
import zio.http.*
import zio.http.codec.*
import zio.ZNothing
import zio.http.endpoint.{AuthType, Endpoint}
import zio.schema.{DeriveSchema, Schema}

/** Declarative descriptions of the admin user-management API — a spike of zio-http's `Endpoint` API against one
  * resource, while every other route file stays on the imperative `Routes`/`handler` DSL.
  *
  * The point of putting these in `shared` is that the path, the status codes and the request/response bodies are stated
  * once and both sides are checked against them: the backend cannot implement an endpoint with the wrong types, and a
  * client built from the same value cannot call it with the wrong ones.
  *
  * The `Endpoint` API codecs come from zio-schema, not the zio-json codecs the DTOs already derive, so the schemas are
  * defined here rather than on the DTO companions — that keeps the second codec stack contained to this package. The
  * two must agree on the wire format; `AdminEndpointsSpec` pins that.
  *
  * Session/CSRF/admin checks are deliberately *not* described here. They are `HandlerAspect`s applied to the whole
  * `Routes` value in the backend, so they stay identical to every other route file.
  */
object AdminEndpoints {

  given Schema[Theme] = DeriveSchema.gen[Theme]
  given Schema[User] = DeriveSchema.gen[User]
  given Schema[CreateUserRequest] = DeriveSchema.gen[CreateUserRequest]
  given Schema[UpdateUserRequest] = DeriveSchema.gen[UpdateUserRequest]

  private val userId = PathCodec.long("id")

  /** Every admin endpoint declares the same six statuses, and it has to be all six on every one of them: a client built
    * from a description can only decode the statuses that description names, so an omitted 401 or 500 reaches the
    * caller as a defect rather than a value. `outErrors` is a fixed-arity overload per error count, not a vararg, so
    * this cannot be a `Seq` of codecs — the shared part is factored out here instead.
    */
  private def withErrors[PathInput, Input, Output](
    endpoint: Endpoint[PathInput, Input, ZNothing, Output, AuthType.None]
  ): Endpoint[PathInput, Input, AdminApiError, Output, AuthType.None] = {
    endpoint.outErrors[AdminApiError](
      HttpCodec.error[AdminApiError.BadRequest](Status.BadRequest),
      HttpCodec.error[AdminApiError.NotFound](Status.NotFound),
      HttpCodec.error[AdminApiError.Conflict](Status.Conflict),
      HttpCodec.error[AdminApiError.Unauthorized](Status.Unauthorized),
      HttpCodec.error[AdminApiError.Forbidden](Status.Forbidden),
      HttpCodec.error[AdminApiError.InternalError](Status.InternalServerError),
    )
  }

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
    * decode and sidesteps it.
    */
  val deleteUser = {
    withErrors(
      Endpoint(Method.DELETE / "api" / "admin" / "users" / userId).outCodec(HttpCodec.status(Status.NoContent))
    )
  }
}
