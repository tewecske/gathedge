package webapp1.backend.http

import webapp1.backend.service.{AdminService, AuthService}
import webapp1.shared.api.AdminEndpoints
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateUserRequest, UpdateUserRequest}
import zio.*
import zio.http.*

/** Administrator user management.
  *
  * Like every other resource, this is implemented against the descriptions in `shared` (`AdminEndpoints`): a handler
  * here is a plain function from the endpoint's input type to its output type, so there is no JSON reading or writing
  * and no way to answer with a body or a status the description doesn't allow. The cross-cutting checks are what stays
  * in this file — `adminOnly` and `csrf` are `HandlerAspect`s applied to the whole `Routes` value, and the acting
  * administrator arrives through `withContext`.
  */
object AdminRoutes {

  private val listUsersRoute = {
    AdminEndpoints.listUsers.implementHandler(handler((_: Unit) => AdminService.listUsers))
  }

  private val getUserRoute = {
    AdminEndpoints.getUser
      .implementHandler(handler((id: Long) => AdminService.getUser(id).mapError(ApiFailures.admin)))
  }

  private val createUserRoute = {
    AdminEndpoints.createUser
      .implementHandler(
        handler { (body: CreateUserRequest) =>
          withContext((actingAdmin: User) => {
            AdminService
              .createUser(actingAdmin.id, body.email, body.password, body.isAdmin)
              .mapError(ApiFailures.admin)
          })
        }
      )
  }

  private val updateUserRoute = {
    AdminEndpoints.updateUser
      .implementHandler(
        handler { (id: Long, body: UpdateUserRequest) =>
          withContext((actingAdmin: User) => {
            AdminService
              .updateUser(actingAdmin.id, id, body.email, body.isAdmin, body.password)
              .mapError(ApiFailures.admin)
          })
        }
      )
  }

  private val deleteUserRoute = {
    AdminEndpoints.deleteUser
      .implementHandler(
        handler { (id: Long) =>
          withContext((actingAdmin: User) => AdminService.deleteUser(actingAdmin.id, id).mapError(ApiFailures.admin))
        }
      )
  }

  val routes: Routes[AuthService & AdminService, Response] = {
    Routes(listUsersRoute, getUserRoute, createUserRoute, updateUserRoute, deleteUserRoute) @@ RouteSupport.adminOnly @@
      RouteSupport.csrf
  }
}
