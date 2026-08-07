package webapp1.frontend.api

import com.raquo.laminar.api.L._
import webapp1.shared.api.AdminEndpoints
import webapp1.shared.domain.{OAuthProvider, User}
import webapp1.shared.dto.{
  AdminUserDetail,
  AuditEntry,
  ClearRateLimitRequest,
  CreateUserRequest,
  LoginAttemptEntry,
  PruneResult,
  RateLimitEntry,
  SystemOverview,
  UpdateUserRequest,
}

import EndpointClient.{executor, run}

/** The admin pages' calls. Split from [[ApiClient]] only because the admin pages are the only callers; it is built the
  * same way, from the descriptions in `shared`.
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

  /** The query parameters are all optional, so a page that wants the default page of everything passes nothing. */
  def auditLog(
    limit: Option[Int] = None,
    before: Option[Long] = None,
    action: Option[String] = None,
    actorId: Option[Long] = None,
    targetId: Option[String] = None,
  ): EventStream[Either[ApiError, List[AuditEntry]]] = {
    run(executor(AdminEndpoints.auditLog(limit, before, action, actorId, targetId)))
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
