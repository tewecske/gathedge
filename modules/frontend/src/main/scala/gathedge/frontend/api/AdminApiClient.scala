package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.AdminEndpoints
import gathedge.shared.domain.{OAuthProvider, User}
import gathedge.shared.dto.{
  AdminUserDetail,
  AuditPage,
  ClearRateLimitRequest,
  CreateUserRequest,
  LoginAttemptEntry,
  PruneResult,
  RateLimitEntry,
  SystemOverview,
  UpdateUserRequest,
  UserPage,
}

import EndpointClient.{executor, run}

/** The admin pages' calls. Split from [[ApiClient]] only because the admin pages are the only callers; it is built the
  * same way, from the descriptions in `shared`.
  */
object AdminApiClient {

  /** One page of accounts. Every parameter is optional and omitting all of them is the first page of everything, in the
    * listing's own order — the server fills the defaults in from `dto.Paging`, which is the same object the page-size
    * dropdown is built from.
    */
  def listUsers(
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
  ): EventStream[Either[ApiError, UserPage]] = {
    run(executor(AdminEndpoints.listUsers(page, pageSize, sort, dir, search)))
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

  def userDetail(id: Long): EventStream[Either[ApiError, AdminUserDetail]] = {
    run(executor(AdminEndpoints.userDetail(id)))
  }

  def verifyUserEmail(id: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(AdminEndpoints.verifyUserEmail(id)))
  }

  def resendUserVerification(id: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(AdminEndpoints.resendUserVerification(id)))
  }

  def revokeUserSessions(id: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(AdminEndpoints.revokeUserSessions(id)))
  }

  def unlinkUserIdentity(id: Long, provider: OAuthProvider): EventStream[Either[ApiError, Unit]] = {
    run(executor(AdminEndpoints.unlinkUserIdentity(id, OAuthProvider.wireName(provider))))
  }

  def clearUserLockout(id: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(AdminEndpoints.clearUserLockout(id)))
  }

  /** The query parameters are all optional, so a page that wants the first page of everything passes nothing. */
  def auditLog(
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    action: Option[String] = None,
    actorId: Option[Long] = None,
    targetId: Option[String] = None,
  ): EventStream[Either[ApiError, AuditPage]] = {
    run(executor(AdminEndpoints.auditLog(page, pageSize, sort, dir, action, actorId, targetId)))
  }

  def loginAttempts(
    limit: Option[Int] = None,
    outcome: Option[String] = None,
  ): EventStream[Either[ApiError, List[LoginAttemptEntry]]] = {
    run(executor(AdminEndpoints.loginAttempts(limit, outcome)))
  }

  def rateLimits: EventStream[Either[ApiError, List[RateLimitEntry]]] = {
    run(executor(AdminEndpoints.rateLimits(())))
  }

  /** `None` clears every key. */
  def clearRateLimits(key: Option[String]): EventStream[Either[ApiError, Unit]] = {
    run(executor(AdminEndpoints.clearRateLimits(ClearRateLimitRequest(key))))
  }

  def systemOverview: EventStream[Either[ApiError, SystemOverview]] = {
    run(executor(AdminEndpoints.systemOverview(())))
  }

  def systemPrune: EventStream[Either[ApiError, PruneResult]] = {
    run(executor(AdminEndpoints.systemPrune(())))
  }
}
