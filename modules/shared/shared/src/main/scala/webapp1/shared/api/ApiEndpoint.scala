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

  /** Attaches an endpoint's declared failures, typing its error channel as the union of exactly those.
    *
    * The union is *inferred from the codecs passed in* rather than written out at the call site, which is the whole
    * reason this exists rather than calling `outErrors` directly. `Endpoint#outErrors[E]` takes the error type as an
    * explicit parameter and only requires each codec's type to be a subtype of it, so
    * `outErrors[BadRequest | NotFound](failure.badRequest)` compiles happily and then fails at *encode* time the first
    * time a handler returns the `NotFound` the codec list never mentioned. Inferring `E` from the arguments makes the
    * two impossible to disagree.
    *
    * A union rather than plain `ApiFailure` is what gives the handler the same guarantee in the other direction: an
    * endpoint that does not declare 409 cannot be implemented by a handler that fails with `ApiFailure.Conflict`,
    * because that no longer conforms to the error type. Under the previous uniform seven-status set it compiled, and
    * the mismatch only showed up as a runtime encoding failure.
    *
    * The error channel of a fresh `Endpoint` is `ZNothing`, and `Alternator.rightEmpty` collapses `E | ZNothing` to
    * `E`, so the result is the bare union with no `Either` nesting. There is one overload per codec count because
    * `outErrors` is itself a fixed-arity overload rather than a vararg; they have to differ in arity within a *single*
    * parameter list, since overload resolution picks the alternative from the first list alone and every codec-count
    * variant would otherwise look identical at the `endpoint` argument.
    */
  extension [P, I, O](endpoint: Endpoint[P, I, ZNothing, O, AuthType.None]) {

    def failingWith[A: ClassTag, B: ClassTag](
      a: ErrorCodec[A],
      b: ErrorCodec[B],
    ): Endpoint[P, I, A | B, O, AuthType.None] = {
      endpoint.outErrors[A | B](a, b)
    }

    def failingWith[A: ClassTag, B: ClassTag, C: ClassTag](
      a: ErrorCodec[A],
      b: ErrorCodec[B],
      c: ErrorCodec[C],
    ): Endpoint[P, I, A | B | C, O, AuthType.None] = {
      endpoint.outErrors[A | B | C](a, b, c)
    }

    def failingWith[A: ClassTag, B: ClassTag, C: ClassTag, D: ClassTag](
      a: ErrorCodec[A],
      b: ErrorCodec[B],
      c: ErrorCodec[C],
      d: ErrorCodec[D],
    ): Endpoint[P, I, A | B | C | D, O, AuthType.None] = {
      endpoint.outErrors[A | B | C | D](a, b, c, d)
    }

    def failingWith[A: ClassTag, B: ClassTag, C: ClassTag, D: ClassTag, E: ClassTag](
      a: ErrorCodec[A],
      b: ErrorCodec[B],
      c: ErrorCodec[C],
      d: ErrorCodec[D],
      e: ErrorCodec[E],
    ): Endpoint[P, I, A | B | C | D | E, O, AuthType.None] = {
      endpoint.outErrors[A | B | C | D | E](a, b, c, d, e)
    }

    def failingWith[A: ClassTag, B: ClassTag, C: ClassTag, D: ClassTag, E: ClassTag, F: ClassTag](
      a: ErrorCodec[A],
      b: ErrorCodec[B],
      c: ErrorCodec[C],
      d: ErrorCodec[D],
      e: ErrorCodec[E],
      f: ErrorCodec[F],
    ): Endpoint[P, I, A | B | C | D | E | F, O, AuthType.None] = {
      endpoint.outErrors[A | B | C | D | E | F](a, b, c, d, e, f)
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
