package webapp1.shared.api

import zio.ZNothing
import zio.http.{Header, Status}
import zio.http.codec.{HeaderCodec, HttpCodec}
import zio.http.endpoint.{AuthType, Endpoint}

/** The pieces every endpoint description in this package is built from.
  *
  * Sessions, the CSRF header and the admin check are deliberately *not* described by any endpoint: they are
  * `HandlerAspect`s applied to whole `Routes` values in the backend, identical for every resource. What they can
  * *answer* with is described, though — see [[withErrors]].
  */
object ApiEndpoint {

  /** Every endpoint declares the same seven statuses.
    *
    * It has to be all seven on every one of them, because a client built from a description can only decode the
    * statuses that description names; an omitted 401 or 500 reaches the caller as a defect rather than a value. Since
    * the aspects and `RouteSupport.handleFailures` can answer 401/403/500 on *any* route, the honest minimum is already
    * most of this list, and stating one uniform set is what makes it impossible for two endpoints to disagree about the
    * status of the same condition. The cost is that a few statuses are documented on endpoints that never raise them
    * (429 outside the auth routes, 409 outside signup and group membership).
    *
    * `outErrors` is a fixed-arity overload per error count, not a vararg, so this cannot be a `Seq` of codecs — the
    * shared part is factored out into this function instead.
    */
  def withErrors[PathInput, Input, Output](
    endpoint: Endpoint[PathInput, Input, ZNothing, Output, AuthType.None]
  ): Endpoint[PathInput, Input, ApiFailure, Output, AuthType.None] = {
    endpoint.outErrors[ApiFailure](
      HttpCodec.error[ApiFailure.BadRequest](Status.BadRequest),
      HttpCodec.error[ApiFailure.Unauthorized](Status.Unauthorized),
      HttpCodec.error[ApiFailure.Forbidden](Status.Forbidden),
      HttpCodec.error[ApiFailure.NotFound](Status.NotFound),
      HttpCodec.error[ApiFailure.Conflict](Status.Conflict),
      HttpCodec.error[ApiFailure.TooManyRequests](Status.TooManyRequests),
      HttpCodec.error[ApiFailure.InternalError](Status.InternalServerError),
    )
  }

  /** The `Set-Cookie` header the three session endpoints answer with, declared *optional* even though the server always
    * sends it.
    *
    * `Set-Cookie` is a forbidden response-header name: a browser applies it and then hides it from `fetch`, so
    * `Response.headers.get("set-cookie")` is null in the page. A required header codec would therefore fail to decode
    * every login the frontend performs, even though the login worked and the cookie was stored. Optional makes the
    * absence a `None` the client ignores — while the JVM client (`AuthFlowSpec`) still sees the real value, and the
    * OpenAPI document still records that these endpoints set a cookie.
    */
  val sessionCookie: HeaderCodec[Option[Header.SetCookie]] = HeaderCodec.setCookie.optional
}
