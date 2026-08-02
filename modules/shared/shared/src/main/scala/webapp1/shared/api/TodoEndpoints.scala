package webapp1.shared.api

import webapp1.shared.domain.TodoItem
import webapp1.shared.dto.{CreateTodoRequest, UpdateTodoStatusRequest}
import zio.http.{Method, Status}
import zio.http.codec.PathCodec
import zio.http.endpoint.Endpoint

import ApiEndpoint.withErrors
import ApiSchemas.given

/** The signed-in user's own todo items. Every one of these requires a session, supplied by the `authenticated` aspect
  * on the `Routes` value rather than described here.
  */
object TodoEndpoints {

  private val todoId = PathCodec.long("id")

  val listTodos = {
    withErrors(Endpoint(Method.GET / "api" / "todos").out[List[TodoItem]])
  }

  val createTodo = {
    withErrors(Endpoint(Method.POST / "api" / "todos").in[CreateTodoRequest].out[TodoItem](Status.Created))
  }

  val updateTodoStatus = {
    withErrors(Endpoint(Method.PUT / "api" / "todos" / todoId / "status").in[UpdateTodoStatusRequest].out[TodoItem])
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(listTodos, createTodo, updateTodoStatus)
}
