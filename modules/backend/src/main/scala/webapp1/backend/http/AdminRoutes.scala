package webapp1.backend.http

import webapp1.backend.service.{AdminFailure, AdminService, AuthService}
import webapp1.shared.api.{AdminApiError, AdminEndpoints}
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateUserRequest, UpdateUserRequest}
import zio.*
import zio.http.*

/** The one resource implemented against the declarative `Endpoint` API (see `shared`'s `AdminEndpoints`); every other
  * route file still builds `Method / path -> handler` by hand.
  *
  * What moved out of this file: the path patterns, the status codes, and all JSON reading and writing. A handler here
  * is a plain function from the endpoint's input type to its output type, so there is no `readJson`, no `jsonResponse`,
  * and no way to answer with a body the description doesn't allow.
  *
  * What stayed: the cross-cutting checks. `adminOnly` and `csrf` are the same `HandlerAspect`s the other route files
  * use, applied to the whole `Routes` value, and the acting administrator still arrives as `ZIO.service[User]`.
  */
object AdminRoutes {

  /** `AdminFailure` is the service's vocabulary; `AdminApiError` is the wire's. The status code is no longer chosen
    * here — it is part of the endpoint description — so this only has to pick the shape and the message.
    */
  private def toApiError(failure: AdminFailure): AdminApiError = {
    failure match {
      case AdminFailure.ValidationError(fieldErrors) =>
        AdminApiError.BadRequest("Validation failed", fieldErrors)
      case AdminFailure.DuplicateEmail =>
        AdminApiError.Conflict("Email already registered", Map("email" -> "Email already registered"))
      case AdminFailure.NotFound =>
        AdminApiError.NotFound("User not found")
      case AdminFailure.SelfDemote =>
        AdminApiError.BadRequest("You cannot remove your own administrator privileges")
      case AdminFailure.SelfDelete =>
        AdminApiError.BadRequest("You cannot delete your own account")
    }
  }

  private val listUsersRoute = {
    AdminEndpoints.listUsers.implementHandler(handler((_: Unit) => ZIO.serviceWithZIO[AdminService](_.listUsers)))
  }

  private val getUserRoute = {
    AdminEndpoints
      .getUser
      .implementHandler(handler((id: Long) => ZIO.serviceWithZIO[AdminService](_.getUser(id)).mapError(toApiError)))
  }

  private val createUserRoute = {
    AdminEndpoints
      .createUser
      .implementHandler(
        handler { (body: CreateUserRequest) =>
          for {
            actingAdmin <- ZIO.service[User]
            adminService <- ZIO.service[AdminService]
            user <- adminService
              .createUser(actingAdmin.id, body.email, body.password, body.isAdmin)
              .mapError(toApiError)
          } yield user
        }
      )
  }

  private val updateUserRoute = {
    AdminEndpoints
      .updateUser
      .implementHandler(
        handler { (id: Long, body: UpdateUserRequest) =>
          for {
            actingAdmin <- ZIO.service[User]
            adminService <- ZIO.service[AdminService]
            user <- adminService
              .updateUser(actingAdmin.id, id, body.email, body.isAdmin, body.password)
              .mapError(toApiError)
          } yield user
        }
      )
  }

  private val deleteUserRoute = {
    AdminEndpoints
      .deleteUser
      .implementHandler(
        handler { (id: Long) =>
          for {
            actingAdmin <- ZIO.service[User]
            adminService <- ZIO.service[AdminService]
            _ <- adminService.deleteUser(actingAdmin.id, id).mapError(toApiError)
          } yield ()
        }
      )
  }

  val routes: Routes[AuthService & AdminService, Response] = {
    Routes(listUsersRoute, getUserRoute, createUserRoute, updateUserRoute, deleteUserRoute) @@ RouteSupport.adminOnly @@
      RouteSupport.csrf
  }
}
