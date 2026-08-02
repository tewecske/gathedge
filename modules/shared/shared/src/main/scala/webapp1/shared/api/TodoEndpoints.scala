package webapp1.shared.api

import webapp1.shared.domain.TodoItem
import webapp1.shared.dto.{CreateTodoRequest, UpdateTodoStatusRequest}
import zio.http.{Method, Status}
import zio.http.codec.PathCodec
import zio.http.endpoint.Endpoint

import ApiEndpoint.failure
import ApiSchemas.given

/** The signed-in user's own todo items. Every one of these requires a session, supplied by the `authenticated` aspect
  * on the `Routes` value rather than described here — which is why they all declare 401 even though no handler raises
  * it. The two mutating ones additionally declare 403, which `csrf` answers for a method outside GET/HEAD/OPTIONS.
  */
object TodoEndpoints {

  private val todoId = PathCodec.long("id")

  /** `TodoService.listTodos` is a `UIO`, so nothing but the aspects and a defect can fail this. */
  val listTodos = {
    Endpoint(Method.GET / "api" / "todos").out[List[TodoItem]].outErrors(failure.unauthorized, failure.internalError)
  }

  val createTodo = {
    Endpoint(Method.POST / "api" / "todos")
      .in[CreateTodoRequest]
      .out[TodoItem](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.internalError)
  }

  val updateTodoStatus = {
    Endpoint(Method.PUT / "api" / "todos" / todoId / "status")
      .in[UpdateTodoStatusRequest]
      .out[TodoItem]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.internalError)
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(listTodos, createTodo, updateTodoStatus)
}
