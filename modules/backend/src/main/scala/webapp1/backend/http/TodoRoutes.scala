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
        handler { (_: Unit) =>
          for {
            user        <- ZIO.service[User]
            todoService <- ZIO.service[TodoService]
            items       <- todoService.listTodos(user.id)
          } yield items
        }
      )
  }

  private val createRoute = {
    TodoEndpoints.createTodo
      .implementHandler(
        handler { (body: CreateTodoRequest) =>
          for {
            user        <- ZIO.service[User]
            todoService <- ZIO.service[TodoService]
            item        <- todoService.addTodo(user.id, body.text).mapError(ApiFailures.todo)
          } yield item
        }
      )
  }

  private val updateStatusRoute = {
    TodoEndpoints.updateTodoStatus
      .implementHandler(
        handler { (id: Long, body: UpdateTodoStatusRequest) =>
          for {
            user        <- ZIO.service[User]
            todoService <- ZIO.service[TodoService]
            item        <- todoService.moveTodo(user.id, id, body.status).mapError(ApiFailures.todo)
          } yield item
        }
      )
  }

  val routes: Routes[AuthService & TodoService, Response] = {
    Routes(listRoute, createRoute, updateStatusRoute) @@ RouteSupport.authenticated @@ RouteSupport.csrf
  }
}
