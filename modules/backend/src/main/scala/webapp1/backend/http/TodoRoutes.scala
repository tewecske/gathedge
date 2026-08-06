package webapp1.backend.http

import webapp1.backend.service.{AuthService, TodoService}
import webapp1.shared.api.TodoEndpoints
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateTodoRequest, UpdateTodoStatusRequest}
import zio.*
import zio.http.*

object TodoRoutes {

  private val listRoute = {
    TodoEndpoints.listTodos
      .implementHandler(
        handler((_: Unit) => withContext((user: User) => TodoService.listTodos(user.id)))
      )
  }

  private val createRoute = {
    TodoEndpoints.createTodo
      .implementHandler(
        handler { (body: CreateTodoRequest) =>
          withContext((user: User) => TodoService.addTodo(user.id, body.text).mapError(ApiFailures.todo))
        }
      )
  }

  private val updateStatusRoute = {
    TodoEndpoints.updateTodoStatus
      .implementHandler(
        handler { (id: Long, body: UpdateTodoStatusRequest) =>
          withContext((user: User) => TodoService.moveTodo(user.id, id, body.status).mapError(ApiFailures.todo))
        }
      )
  }

  val routes: Routes[AuthService & TodoService, Response] = {
    Routes(listRoute, createRoute, updateStatusRoute) @@ RouteSupport.authenticated @@ RouteSupport.csrf
  }
}
