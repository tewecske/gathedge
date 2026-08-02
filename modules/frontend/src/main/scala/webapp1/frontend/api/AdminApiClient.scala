package webapp1.frontend.api

import com.raquo.laminar.api.L._
import webapp1.shared.api.AdminEndpoints
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateUserRequest, UpdateUserRequest}

import EndpointClient.{executor, run}

/** The admin pages' calls. Split from [[ApiClient]] only because the two admin pages are the only callers; it is built
  * the same way, from the descriptions in `shared`.
  */
object AdminApiClient {

  def listUsers: EventStream[Either[ApiError, List[User]]] = {
    run(executor(AdminEndpoints.listUsers(())))
  }

  def getUser(id: Long): EventStream[Either[ApiError, User]] = {
    run(executor(AdminEndpoints.getUser(id)))
  }

  def createUser(request: CreateUserRequest): EventStream[Either[ApiError, User]] = {
    run(executor(AdminEndpoints.createUser(request)))
  }

  def updateUser(id: Long, request: UpdateUserRequest): EventStream[Either[ApiError, User]] = {
    run(executor(AdminEndpoints.updateUser(id, request)))
  }

  def deleteUser(id: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(AdminEndpoints.deleteUser(id)))
  }
}
