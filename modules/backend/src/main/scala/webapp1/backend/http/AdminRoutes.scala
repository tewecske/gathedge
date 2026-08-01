package webapp1.backend.http

import webapp1.backend.service.{AdminFailure, AdminService, AuthService}
import webapp1.shared.dto.{CreateUserRequest, UpdateUserRequest}
import zio.*
import zio.http.*

import JsonSupport.*
import RouteSupport.{csrfCheck, requireAdmin}

object AdminRoutes {

  private def adminFailureResponse(failure: AdminFailure): Response = {
    failure match {
      case AdminFailure.ValidationError(fieldErrors) =>
        errorResponse(Status.BadRequest, "Validation failed", fieldErrors)
      case AdminFailure.DuplicateEmail =>
        errorResponse(Status.Conflict, "Email already registered", Map("email" -> "Email already registered"))
      case AdminFailure.NotFound =>
        errorResponse(Status.NotFound, "User not found")
      case AdminFailure.SelfDemote =>
        errorResponse(Status.BadRequest, "You cannot remove your own administrator privileges")
      case AdminFailure.SelfDelete =>
        errorResponse(Status.BadRequest, "You cannot delete your own account")
    }
  }

  private val listUsersRoute = {
    Method.GET / "api" / "admin" / "users" ->
      handler { (request: Request) =>
        (
          for {
            _ <- requireAdmin(request)
            adminService <- ZIO.service[AdminService]
            users <- adminService.listUsers
          } yield jsonResponse(Status.Ok, users)
        ).merge
      }
  }

  private val createUserRoute = {
    Method.POST / "api" / "admin" / "users" ->
      handler { (request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            actingAdmin <- requireAdmin(request)
            body <- readJson[CreateUserRequest](request)
            adminService <- ZIO.service[AdminService]
            user <- adminService
              .createUser(actingAdmin.id, body.email, body.password, body.isAdmin)
              .mapError(adminFailureResponse)
          } yield jsonResponse(Status.Created, user)
        ).merge
      }
  }

  private val getUserRoute = {
    Method.GET / "api" / "admin" / "users" / long("id") ->
      handler { (id: Long, request: Request) =>
        (
          for {
            _ <- requireAdmin(request)
            adminService <- ZIO.service[AdminService]
            user <- adminService.getUser(id).mapError(adminFailureResponse)
          } yield jsonResponse(Status.Ok, user)
        ).merge
      }
  }

  private val updateUserRoute = {
    Method.PUT / "api" / "admin" / "users" / long("id") ->
      handler { (id: Long, request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            actingAdmin <- requireAdmin(request)
            body <- readJson[UpdateUserRequest](request)
            adminService <- ZIO.service[AdminService]
            user <- adminService
              .updateUser(actingAdmin.id, id, body.email, body.isAdmin, body.password)
              .mapError(adminFailureResponse)
          } yield jsonResponse(Status.Ok, user)
        ).merge
      }
  }

  private val deleteUserRoute = {
    Method.DELETE / "api" / "admin" / "users" / long("id") ->
      handler { (id: Long, request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            actingAdmin <- requireAdmin(request)
            adminService <- ZIO.service[AdminService]
            _ <- adminService.deleteUser(actingAdmin.id, id).mapError(adminFailureResponse)
          } yield Response(status = Status.NoContent)
        ).merge
      }
  }

  val routes: Routes[AuthService & AdminService, Nothing] = Routes(
    listUsersRoute,
    createUserRoute,
    getUserRoute,
    updateUserRoute,
    deleteUserRoute,
  )
}
