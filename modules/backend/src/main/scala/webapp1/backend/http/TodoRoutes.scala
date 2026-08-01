package webapp1.backend.http

import webapp1.backend.service.{AuthService, TodoFailure, TodoService}
import webapp1.shared.dto.{CreateTodoRequest, UpdateTodoStatusRequest}
import zio.*
import zio.http.*

import JsonSupport.*
import RouteSupport.{authenticatedUser, csrfCheck}

object TodoRoutes {

  private def todoFailureResponse(failure: TodoFailure): Response = {
    failure match {
      case TodoFailure.ValidationError(message) =>
        errorResponse(Status.BadRequest, message, Map("text" -> message))
      case TodoFailure.NotFound =>
        errorResponse(Status.NotFound, "Todo item not found")
    }
  }

  private val listRoute = {
    Method.GET / "api" / "todos" ->
      handler { (request: Request) =>
        (
          for {
            user <- authenticatedUser(request)
            todoService <- ZIO.service[TodoService]
            items <- todoService.listTodos(user.id)
          } yield jsonResponse(Status.Ok, items)
        ).merge
      }
  }

  private val createRoute = {
    Method.POST / "api" / "todos" ->
      handler { (request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            user <- authenticatedUser(request)
            body <- readJson[CreateTodoRequest](request)
            todoService <- ZIO.service[TodoService]
            item <- todoService.addTodo(user.id, body.text).mapError(todoFailureResponse)
          } yield jsonResponse(Status.Created, item)
        ).merge
      }
  }

  private val updateStatusRoute = {
    Method.PUT / "api" / "todos" / long("id") / "status" ->
      handler { (id: Long, request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            user <- authenticatedUser(request)
            body <- readJson[UpdateTodoStatusRequest](request)
            todoService <- ZIO.service[TodoService]
            item <- todoService.moveTodo(user.id, id, body.status).mapError(todoFailureResponse)
          } yield jsonResponse(Status.Ok, item)
        ).merge
      }
  }

  val routes: Routes[AuthService & TodoService, Nothing] = Routes(listRoute, createRoute, updateStatusRoute)
}
