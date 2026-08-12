package webapp1.frontend.api

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.i18n.{CurrentLocale, I18n}
import webapp1.shared.api.ApiFailure
import webapp1.shared.domain.Locale.code
import webapp1.shared.i18n.{MessageKeys, MessageRef}
import zio._
import zio.http.{Client, URL}
import zio.http.endpoint.EndpointExecutor

import scala.concurrent.ExecutionContext.Implicits.global

/** What every API call in this app goes through: the seam between zio-http's endpoint executor and Airstream.
  *
  * Nothing here knows a path or a method. Those come from the descriptions in `shared`, which the backend is
  * implemented against, so a rename or a changed body on the server breaks the callers at compile time instead of at
  * runtime in the browser.
  */
object EndpointClient {

  /** zio-http's Scala.js client is a `fetch` driver. Building it is a scoped effect, so the runtime — not a bare
    * `Unsafe.run` — has to own it for the lifetime of the page.
    */
  private val runtime: Runtime[Client] = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.unsafe.fromLayer(Client.default)
    }
  }

  /** Endpoints are invoked through this: `executor(SomeEndpoints.someEndpoint(input))`. */
  val executor: EndpointExecutor[Any, Unit, Scope] = {
    Unsafe.unsafe { implicit unsafe =>
      val client = runtime.unsafe.run(ZIO.service[Client]).getOrThrow()
      val base   = URL.decode(dom.window.location.origin).toOption.get
      // Neither of these is part of any endpoint description, so both are set here — once, on the
      // client, rather than per call. CSRF is a HandlerAspect on the server; `X-Locale` is read by
      // `RouteSupport.requestContext` and tells the server which language this page is running in,
      // which is what the two transactional emails are written in. Describing it would mean a header
      // codec on every endpoint that might send mail, kept in step by hand.
      EndpointExecutor(
        client
          .addHeader("X-Requested-With", "XMLHttpRequest")
          .addHeader("X-Locale", CurrentLocale.value.code),
        base,
      )
    }
  }

  /** Flattens the typed failure back to a status number, which is what pages branch on, and words it.
    *
    * '''This is where server messages become the reader's language.''' Every `ApiFailure` arrives as a catalog key plus
    * arguments — the server chooses the key and never a language — and it is resolved here rather than in the pages, so
    * a page keeps rendering `err.message` and needs to know nothing about i18n. The English `message` the server sends
    * alongside the key is for callers with no catalog and is deliberately ignored.
    *
    * Only `BadRequest` carries `fieldErrors`; for the rest the map is empty because the failure is about the request as
    * a whole rather than about one of its inputs.
    *
    * Every case is matched because `ApiFailure` is sealed, not because every case arrives: no endpoint declares 500 and
    * only `POST /api/auth/login` declares 403, so those two responses reach [[run]] as an undeclared status instead —
    * see `ApiEndpoint.failure` for why they are left out of the descriptions.
    */
  private def toApiError(failure: ApiFailure): ApiError = {
    def worded(status: Int, error: MessageRef, fieldErrors: Map[String, MessageRef] = Map.empty): ApiError = {
      ApiError(status, I18n.resolve(error), fieldErrors.view.mapValues(I18n.resolve).toMap)
    }

    failure match {
      case ApiFailure.BadRequest(error, _, fieldErrors) =>
        worded(400, error, fieldErrors)
      case ApiFailure.Unauthorized(error, _)            =>
        worded(401, error)
      case ApiFailure.Forbidden(error, _)               =>
        worded(403, error)
      case ApiFailure.NotFound(error, _)                =>
        worded(404, error)
      case ApiFailure.Conflict(error, _)                =>
        worded(409, error)
      case ApiFailure.TooManyRequests(error, _)         =>
        worded(429, error)
      case ApiFailure.InternalError(error, _)           =>
        worded(500, error)
    }
  }

  /** The one bridge from ZIO to Airstream. Every endpoint's error channel is a union of `ApiFailure` cases, so a
    * failure arrives typed and exhaustively matchable; anything the endpoint did not declare (a defect, a dead socket,
    * an undeclared status) lands in the cause and is flattened into the same `ApiError` shape, so a caller never has to
    * handle two kinds of failure. Callers get an `EventStream[Either[ApiError, A]]` per the laminar skill's
    * explicit-flattening convention — a failure is a value on the success path, never a stream error, so an
    * `onMountCallback`-driven load cannot hang on a rejected promise.
    *
    * The parameter is widened to `ApiFailure` rather than each endpoint's own union: `ZIO`'s error channel is
    * covariant, so every call site conforms without naming its union here.
    */
  def run[A](effect: ZIO[Scope, ApiFailure, A]): EventStream[Either[ApiError, A]] = {
    val future = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .runToFuture(
          ZIO
            .scoped(effect)
            .either
            .map(_.left.map(toApiError))
            .catchAllCause(cause => {
              // No answer at all: offline, a dead socket, an undeclared status. The console keeps the
              // detail; the user gets something they can read, because the underlying message is a
              // stack-trace fragment in whatever language the runtime happens to speak.
              ZIO.succeed {
                dom.console.warn(s"Request failed: ${cause.squash.getMessage}")
                Left(ApiError(0, I18n.t(MessageKeys.requestFailed), Map.empty))
              }
            })
        )
    }
    EventStream.fromFuture(future)
  }
}
