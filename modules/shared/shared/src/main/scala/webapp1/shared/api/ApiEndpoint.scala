package webapp1.shared.api

import scala.reflect.ClassTag

import zio.ZNothing
import zio.http.{Header, Status}
import zio.http.codec.{HeaderCodec, HttpCodec, HttpCodecType}
import zio.http.endpoint.{AuthType, Endpoint}

/** The pieces every endpoint description in this package is built from.
  *
  * Sessions, the CSRF header and the admin check are deliberately *not* described by any endpoint: they are
  * `HandlerAspect`s applied to whole `Routes` values in the backend, identical for every resource. What they can
  * *answer* with is described, though — see [[failure]] and [[failingWith]].
  */
object ApiEndpoint {

  private type ErrorCodec[A] = HttpCodec[HttpCodecType.Status & HttpCodecType.Content, A]

  /** The status-to-body codecs an endpoint picks its declared failures from.
    *
    * Each endpoint names only the ones it can actually answer with. Two rules decide the list:
    *
    *   - whatever its own handler can raise, i.e. the cases the matching `ApiFailures` mapper produces (nothing at all
    *     for a handler whose service call cannot fail);
    *   - plus whatever the aspects wrapped around its `Routes` value can answer *instead of* running the handler —
    *     [[unauthorized]] under `authenticated`/`adminOnly`, [[forbidden]] under `adminOnly` or under `csrf` for a
    *     method outside GET/HEAD/OPTIONS, and [[internalError]] everywhere, since `RouteSupport.handleFailures` turns a
    *     defect on any route into a 500.
    *
    * The second group is why a description cannot simply list what its handler raises: those responses never pass
    * through the endpoint's codecs on the way out, but a client built from the description still has to decode them. A
    * status the description omits is not decodable at all — the endpoint client fails such a response as a *defect*
    * carrying "Expected status code ... but found ...", so an expired session would surface as an unrenderable crash
    * instead of a 401 the caller can act on.
    */
  object failure {
    val badRequest: ErrorCodec[ApiFailure.BadRequest] = HttpCodec.error[ApiFailure.BadRequest](Status.BadRequest)
    val unauthorized: ErrorCodec[ApiFailure.Unauthorized] = HttpCodec.error[ApiFailure.Unauthorized](
      Status.Unauthorized
    )
    val forbidden: ErrorCodec[ApiFailure.Forbidden] = HttpCodec.error[ApiFailure.Forbidden](Status.Forbidden)
    val notFound: ErrorCodec[ApiFailure.NotFound] = HttpCodec.error[ApiFailure.NotFound](Status.NotFound)
    val conflict: ErrorCodec[ApiFailure.Conflict] = HttpCodec.error[ApiFailure.Conflict](Status.Conflict)

    val tooManyRequests: ErrorCodec[ApiFailure.TooManyRequests] = {
      HttpCodec.error[ApiFailure.TooManyRequests](Status.TooManyRequests)
    }

    val internalError: ErrorCodec[ApiFailure.InternalError] = {
      HttpCodec.error[ApiFailure.InternalError](Status.InternalServerError)
    }
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
