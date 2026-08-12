package webapp1.shared.api

import webapp1.shared.i18n.{MessageKeys, MessageRef}

import scala.reflect.ClassTag

import zio.ZNothing
import zio.http.{Header, Status}
import zio.http.codec.{Alternator, HeaderCodec, HttpCodec, HttpCodecError, HttpCodecType}
import zio.http.endpoint.{AuthType, Endpoint}

/** The pieces every endpoint description in this package is built from.
  *
  * Sessions, the CSRF header and the admin check are deliberately *not* described by any endpoint: they are
  * `HandlerAspect`s applied to whole `Routes` values in the backend, identical for every resource. Of what they can
  * *answer* with, only the session's 401 is described — see [[failure]] for which statuses are left out and why.
  */
object ApiEndpoint {

  private type ErrorCodec[A] = HttpCodec[HttpCodecType.Status & HttpCodecType.Content, A]

  /** The status-to-body codecs an endpoint picks its declared failures from.
    *
    * An endpoint declares a failure when it is an *answer to a well-formed request* — something a caller acting in good
    * faith can receive and act on. That is: whatever its own handler can raise, i.e. the cases the matching
    * `ApiFailures` mapper produces (nothing at all for a handler whose service call cannot fail), plus [[unauthorized]]
    * wherever `authenticated`/`adminOnly` guards it, because a session expiring under a page that was legitimately open
    * is the ordinary course of events.
    *
    * Three statuses are deliberately **left undescribed**, even though the server can still send them:
    *
    *   - **403 from an aspect** — `csrf` rejecting a mutating request with no `X-Requested-With`, or `adminOnly`
    *     rejecting a non-administrator. The generated client always sends the header and no page routes a
    *     non-administrator to an admin call, so reaching either means a client that is not the one these descriptions
    *     generated. (403 *is* described on `POST /api/auth/login` — there it comes from the service rather than from an
    *     aspect, and "confirm your address first" is an answer, not a rejection. A feature whose service raises a
    *     permission failure of its own should describe it the same way.)
    *   - **429** — the rate limiter, which only signup and login pass through. Still described on those two, because
    *     `ApiFailures.auth` maps `AuthFailure.RateLimited` onto it and the sign-in form renders the message; there is
    *     nowhere else in the API it can occur.
    *   - **500** — `RouteSupport.handleFailures` turning a defect on any route into one. A bug or a dead database is
    *     not part of the API's contract, and describing it on all 25 operations said nothing an operation-specific
    *     document should say.
    *
    * The cost of omitting a status is real and is the reason 401 is *not* in that list: a status a description omits is
    * not decodable at all. The endpoint client fails such a response as a *defect* carrying "Expected status code ...
    * but found ...", which `EndpointClient.run` flattens into `ApiError(0, "Request failed: ...")` — a generic error
    * with no status for a page to branch on. That is the right shape for a CSRF rejection or a 500, and the wrong one
    * for an expired session, which every authenticated page has to recognise.
    */
  object failure {
    val badRequest: ErrorCodec[ApiFailure.BadRequest]     = HttpCodec.error[ApiFailure.BadRequest](Status.BadRequest)
    val unauthorized: ErrorCodec[ApiFailure.Unauthorized] = HttpCodec.error[ApiFailure.Unauthorized](
      Status.Unauthorized
    )
    val forbidden: ErrorCodec[ApiFailure.Forbidden]       = HttpCodec.error[ApiFailure.Forbidden](Status.Forbidden)
    val notFound: ErrorCodec[ApiFailure.NotFound]         = HttpCodec.error[ApiFailure.NotFound](Status.NotFound)
    val conflict: ErrorCodec[ApiFailure.Conflict]         = HttpCodec.error[ApiFailure.Conflict](Status.Conflict)

    val tooManyRequests: ErrorCodec[ApiFailure.TooManyRequests] = {
      HttpCodec.error[ApiFailure.TooManyRequests](Status.TooManyRequests)
    }
  }

  /** The body an endpoint answers with when its *own codecs* reject the request, before any handler runs.
    *
    * `Endpoint` carries two error codecs. `outErrors` builds the one for what the handler raises; `codecError` is the
    * other, for what fails before the handler is ever called — a body that does not parse, a wrong `Content-Type`, a
    * header or query codec that does not decode. `Endpoint.implementHandler` catches that `HttpCodecError` itself and
    * encodes it on the spot, so it reaches neither `ApiFailures` nor `RouteSupport.handleFailures` nor `JsonSupport`,
    * and its status is fixed at 400 by the library.
    *
    * Left alone it answers `HttpContentCodec.responseErrorCodec` — a private `{"name", "message"}` shape that is not
    * `dto.ErrorResponse`, offered as `text/html` ahead of `application/json`. Routing it through [[failure.badRequest]]
    * makes a rejected request answer exactly what a rejected *handler* answers, which is the only thing a caller built
    * from these descriptions can decode.
    *
    * The message is fixed rather than taken from the error: `HttpCodecError.getMessage` carries zio-schema decode paths
    * and header names, which are internals of the description rather than something a caller can act on. Nothing logs
    * the discarded detail — `transformOrFail` is a pure function and zio-http offers no effectful hook at that point.
    *
    * The decode direction is only used by a *client*, for a status matching neither the output nor the declared errors,
    * and it feeds `ZIO.die`; `CustomError` is the one case that round-trips, which is all that branch needs.
    */
  private val codecError: HttpCodec[HttpCodecType.ResponseType, HttpCodecError] = {
    failure.badRequest
      .transformOrFail[HttpCodecError](bad => Right(HttpCodecError.CustomError("BadRequest", bad.message)))(_ =>
        Right(ApiFailure.BadRequest(MessageRef(MessageKeys.malformedRequest), "Malformed request"))
      )
  }

  /** Installs [[codecError]] on an endpoint, **replacing** the library default rather than falling back to it.
    *
    * `Endpoint.outCodecError` would combine the two as `codec | self.codecError`, and content negotiation then hands a
    * caller sending `Accept: text/html` back to the default, because that one has an HTML branch and this one does not
    * — the exact case worth closing. Replacing the field outright leaves one shape for every caller: with no HTML
    * branch to find, `HttpContentCodec.chooseFirstOrDefault` falls back to this codec's own default media type, which
    * is `application/json`.
    *
    * Applied per endpoint rather than by wrapping `Endpoint.apply`, so an endpoint that later gains an input, a query
    * parameter or a header codec has to apply it too. That is the drift this shape accepts.
    */
  extension [P, I, E, O, A <: AuthType](endpoint: Endpoint[P, I, E, O, A]) {
    def withCodecError: Endpoint[P, I, E, O, A] = endpoint.copy(codecError = codecError)

    /** Declares a single failure, which `outErrors` cannot: its smallest `apply` overload takes two codecs.
      *
      * Now that an endpoint only describes what a well-behaved caller can receive (see [[failure]]), several are down
      * to one — the session's 401 — and the alternative is `Endpoint.outError[ApiFailure.Unauthorized](
      * Status.Unauthorized)`, which builds an equivalent codec while naming the status a second time outside
      * [[failure]]. This installs [[failure]]'s own codec instead, the same way `outError` and `outErrors` both do.
      */
    def outFailure[E2](codec: ErrorCodec[E2])(implicit alt: Alternator[E2, E]): Endpoint[P, I, alt.Out, O, A] = {
      endpoint.copy(error = codec | endpoint.error)
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
