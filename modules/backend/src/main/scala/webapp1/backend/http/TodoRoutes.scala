package webapp1.backend.http

import webapp1.backend.service.{AuthService, TodoService}
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateTodoRequest, UpdateTodoStatusRequest}
import zio.*
import zio.http.*

import JsonSupport.*

object TodoRoutes {

  private val listRoute = {
    Method.GET / "api" / "todos" ->
      handler { (_: Request) =>
        for {
          user <- ZIO.service[User]
          todoService <- ZIO.service[TodoService]
          items <- todoService.listTodos(user.id)
        } yield jsonResponse(Status.Ok, items)
      }
  }

  private val createRoute = {
    Method.POST / "api" / "todos" ->
      handler { (request: Request) =>
        for {
          user <- ZIO.service[User]
          body <- readJson[CreateTodoRequest](request)
          todoService <- ZIO.service[TodoService]
          item <- todoService.addTodo(user.id, body.text).mapError(FailureResponses.todo)
        } yield jsonResponse(Status.Created, item)
      }
  }

  private val updateStatusRoute = {
    Method.PUT / "api" / "todos" / long("id") / "status" ->
      handler { (id: Long, request: Request) =>
        for {
          user <- ZIO.service[User]
          body <- readJson[UpdateTodoStatusRequest](request)
          todoService <- ZIO.service[TodoService]
          item <- todoService.moveTodo(user.id, id, body.status).mapError(FailureResponses.todo)
        } yield jsonResponse(Status.Ok, item)
      }
  }

  val routes: Routes[AuthService & TodoService, Response] = {
    Routes(listRoute, createRoute, updateStatusRoute) @@ RouteSupport.authenticated @@ RouteSupport.csrf
  }
}
