package webapp1.backend.http

import webapp1.backend.service.{AdminService, AuthService}
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateUserRequest, UpdateUserRequest}
import zio.*
import zio.http.*

import JsonSupport.*

object AdminRoutes {

  private val listUsersRoute = {
    Method.GET / "api" / "admin" / "users" ->
      handler { (_: Request) =>
        for {
          adminService <- ZIO.service[AdminService]
          users <- adminService.listUsers
        } yield jsonResponse(Status.Ok, users)
      }
  }

  private val createUserRoute = {
    Method.POST / "api" / "admin" / "users" ->
      handler { (request: Request) =>
        for {
          actingAdmin <- ZIO.service[User]
          body <- readJson[CreateUserRequest](request)
          adminService <- ZIO.service[AdminService]
          user <- adminService
            .createUser(actingAdmin.id, body.email, body.password, body.isAdmin)
            .mapError(FailureResponses.admin)
        } yield jsonResponse(Status.Created, user)
      }
  }

  private val getUserRoute = {
    Method.GET / "api" / "admin" / "users" / long("id") ->
      handler { (id: Long, _: Request) =>
        for {
          adminService <- ZIO.service[AdminService]
          user <- adminService.getUser(id).mapError(FailureResponses.admin)
        } yield jsonResponse(Status.Ok, user)
      }
  }

  private val updateUserRoute = {
    Method.PUT / "api" / "admin" / "users" / long("id") ->
      handler { (id: Long, request: Request) =>
        for {
          actingAdmin <- ZIO.service[User]
          body <- readJson[UpdateUserRequest](request)
          adminService <- ZIO.service[AdminService]
          user <- adminService
            .updateUser(actingAdmin.id, id, body.email, body.isAdmin, body.password)
            .mapError(FailureResponses.admin)
        } yield jsonResponse(Status.Ok, user)
      }
  }

  private val deleteUserRoute = {
    Method.DELETE / "api" / "admin" / "users" / long("id") ->
      handler { (id: Long, _: Request) =>
        for {
          actingAdmin <- ZIO.service[User]
          adminService <- ZIO.service[AdminService]
          _ <- adminService.deleteUser(actingAdmin.id, id).mapError(FailureResponses.admin)
        } yield Response(status = Status.NoContent)
      }
  }

  val routes: Routes[AuthService & AdminService, Response] = {
    Routes(listUsersRoute, createUserRoute, getUserRoute, updateUserRoute, deleteUserRoute) @@ RouteSupport.adminOnly @@
      RouteSupport.csrf
  }
}
