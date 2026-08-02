package webapp1.frontend.api

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.shared.api.ApiFailure
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
      val base = URL.decode(dom.window.location.origin).toOption.get
      // CSRF is not part of any endpoint description (it is a HandlerAspect on the server), so the header still has
      // to be set here — once, on the client, rather than per call.
      EndpointExecutor(client.addHeader("X-Requested-With", "XMLHttpRequest"), base)
    }
  }

  private def toApiError(failure: ApiFailure): ApiError = {
    failure match {
      case ApiFailure.BadRequest(message, fieldErrors) =>
        ApiError(400, message, fieldErrors)
      case ApiFailure.Unauthorized(message, fieldErrors) =>
        ApiError(401, message, fieldErrors)
      case ApiFailure.Forbidden(message, fieldErrors) =>
        ApiError(403, message, fieldErrors)
      case ApiFailure.NotFound(message, fieldErrors) =>
        ApiError(404, message, fieldErrors)
      case ApiFailure.Conflict(message, fieldErrors) =>
        ApiError(409, message, fieldErrors)
      case ApiFailure.TooManyRequests(message, fieldErrors) =>
        ApiError(429, message, fieldErrors)
      case ApiFailure.InternalError(message, fieldErrors) =>
        ApiError(500, message, fieldErrors)
    }
  }

  /** The one bridge from ZIO to Airstream. `ApiFailure` is the declared error channel, so it arrives typed and
    * exhaustively matchable; anything no description mentions (a defect, a dead socket, a status nobody declared) lands
    * in the cause and is flattened into the same `ApiError` shape, so a caller never has to handle two kinds of
    * failure. Callers get an `EventStream[Either[ApiError, A]]` per the laminar skill's explicit-flattening convention
    * — a failure is a value on the success path, never a stream error, so an `onMountCallback`-driven load cannot hang
    * on a rejected promise.
    */
  def run[A](effect: ZIO[Scope, ApiFailure, A]): EventStream[Either[ApiError, A]] = {
    val future = Unsafe.unsafe { implicit unsafe =>
      runtime
        .unsafe
        .runToFuture(
          ZIO
            .scoped(effect)
            .either
            .map(_.left.map(toApiError))
            .catchAllCause(cause =>
              ZIO.succeed(Left(ApiError(0, s"Request failed: ${cause.squash.getMessage}", Map.empty)))
            )
        )
    }
    EventStream.fromFuture(future)
  }
}
