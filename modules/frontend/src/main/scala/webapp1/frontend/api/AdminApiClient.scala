package webapp1.frontend.api

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.shared.api.{AdminApiError, AdminEndpoints}
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateUserRequest, UpdateUserRequest}
import zio._
import zio.http.{Client, URL}
import zio.http.endpoint.EndpointExecutor

import scala.concurrent.ExecutionContext.Implicits.global

/** The admin pages' API client, generated from the descriptions in `shared`'s `AdminEndpoints` rather than written by
  * hand like `ApiClient`. Paths, methods, request/response types and the error statuses all come from the one value the
  * backend is implemented against, so a rename or a changed body on the server breaks this at compile time.
  *
  * Everything else on the frontend still goes through `ApiClient`; only the admin resource is described declaratively.
  *
  * The seam is the boundary between two effect systems: endpoints are invoked as ZIO effects, and everything Laminar
  * consumes is an `EventStream`, so `run` is the single place an effect is handed to the runtime and turned back into a
  * stream. Callers see the same `EventStream[Either[ApiError, A]]` the rest of the app already uses.
  */
object AdminApiClient {

  /** zio-http's Scala.js client is a `fetch` driver. Building it is a scoped effect, so the runtime — not a bare
    * `Unsafe.run` — has to own it for the lifetime of the page.
    */
  private val runtime: Runtime[Client] = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.unsafe.fromLayer(Client.default)
    }
  }

  private val executor: EndpointExecutor[Any, Unit, Scope] = {
    Unsafe.unsafe { implicit unsafe =>
      val client = runtime.unsafe.run(ZIO.service[Client]).getOrThrow()
      val base = URL.decode(dom.window.location.origin).toOption.get
      // CSRF is not part of the endpoint description (it is a HandlerAspect on the server), so the header still has
      // to be set here — once, on the client, rather than per call.
      EndpointExecutor(client.addHeader("X-Requested-With", "XMLHttpRequest"), base)
    }
  }

  private def toApiError(error: AdminApiError): ApiError = {
    error match {
      case AdminApiError.BadRequest(message, fieldErrors) =>
        ApiError(400, message, fieldErrors)
      case AdminApiError.NotFound(message, fieldErrors) =>
        ApiError(404, message, fieldErrors)
      case AdminApiError.Conflict(message, fieldErrors) =>
        ApiError(409, message, fieldErrors)
      case AdminApiError.Unauthorized(message, fieldErrors) =>
        ApiError(401, message, fieldErrors)
      case AdminApiError.Forbidden(message, fieldErrors) =>
        ApiError(403, message, fieldErrors)
      case AdminApiError.InternalError(message, fieldErrors) =>
        ApiError(500, message, fieldErrors)
    }
  }

  /** The one bridge from ZIO to Airstream. `AdminApiError` is the declared error channel, so it arrives typed and
    * exhaustively matchable; anything the description does not mention (a 401 from the session aspect, a defect, a dead
    * socket) lands in the cause and is flattened into the same `ApiError` shape `ApiClient` returns.
    */
  private def run[A](effect: ZIO[Scope, AdminApiError, A]): EventStream[Either[ApiError, A]] = {
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

  def listUsers: EventStream[Either[ApiError, List[User]]] = {
    run(executor(AdminEndpoints.listUsers(())))
  }

  def getUser(id: Long): EventStream[Either[ApiError, User]] = {
    run(executor(AdminEndpoints.getUser(id)))
  }

  def createUser(request: CreateUserRequest): EventStream[Either[ApiError, User]] = {
    run(executor(AdminEndpoints.createUser(request)))
  }

  def updateUser(id: Long, request: UpdateUserRequest): EventStream[Either[ApiError, User]] = {
    run(executor(AdminEndpoints.updateUser(id, request)))
  }

  def deleteUser(id: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(AdminEndpoints.deleteUser(id)))
  }
}
