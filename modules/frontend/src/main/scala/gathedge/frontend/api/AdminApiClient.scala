package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.domain.{OAuthProvider, User}
import gathedge.shared.dto.{
  AdminUserDetail,
  AuditPage,
  ClearRateLimitRequest,
  CreateUserRequest,
  DeleteWordFormRequest,
  GameResults,
  LoginAttemptEntry,
  MyPlayPage,
  PruneResult,
  RateLimitEntry,
  RouteUsage,
  SuspiciousUser,
  SystemOverview,
  UpdateUserRequest,
  UserPage,
  WordFormAnomaly,
}
import zio.json._

import HttpClient.{query, segment}

/** The admin pages' calls. Split from [[ApiClient]] only because the admin pages are the only callers; it is built the
  * same way, over [[HttpClient]].
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
    HttpClient.get[UserPage](
      s"/api/admin/users${query("page" -> page, "pageSize" -> pageSize, "sort" -> sort, "dir" -> dir, "q" -> search)}"
    )
  }

  def getUser(id: Long): EventStream[Either[ApiError, User]] = {
    HttpClient.get[User](s"/api/admin/users/$id")
  }

  def createUser(request: CreateUserRequest): EventStream[Either[ApiError, User]] = {
    HttpClient.post[User]("/api/admin/users", Some(request.toJson))
  }

  def updateUser(id: Long, request: UpdateUserRequest): EventStream[Either[ApiError, User]] = {
    HttpClient.put[User](s"/api/admin/users/$id", Some(request.toJson))
  }

  def deleteUser(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/admin/users/$id")
  }

  def userDetail(id: Long): EventStream[Either[ApiError, AdminUserDetail]] = {
    HttpClient.get[AdminUserDetail](s"/api/admin/users/$id/detail")
  }

  /** One page of `id`'s game plays across every game. `search` is a case-insensitive substring of the game's name. */
  def userPlays(
    id: Long,
    gameId: Option[Long] = None,
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
  ): EventStream[Either[ApiError, MyPlayPage]] = {
    HttpClient.get[MyPlayPage](
      s"/api/admin/users/$id/plays${query(
          "gameId"   -> gameId,
          "page"     -> page,
          "pageSize" -> pageSize,
          "sort"     -> sort,
          "dir"      -> dir,
          "q"        -> search,
        )}"
    )
  }

  /** One of `id`'s finished plays, with its score and full answer history — the admin-scoped counterpart of
    * `GameApiClient.getResults`, which is owner-only.
    */
  def userPlayResults(id: Long, playId: Long): EventStream[Either[ApiError, GameResults]] = {
    HttpClient.get[GameResults](s"/api/admin/users/$id/plays/$playId/results")
  }

  def verifyUserEmail(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, s"/api/admin/users/$id/verify-email")
  }

  def resendUserVerification(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, s"/api/admin/users/$id/verification/resend")
  }

  def revokeUserSessions(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/admin/users/$id/sessions")
  }

  def unlinkUserIdentity(id: Long, provider: OAuthProvider): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/admin/users/$id/identities/${segment(OAuthProvider.wireName(provider))}")
  }

  def clearUserLockout(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/admin/users/$id/lockout")
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
    HttpClient.get[AuditPage](
      s"/api/admin/audit${query(
          "page"     -> page,
          "pageSize" -> pageSize,
          "sort"     -> sort,
          "dir"      -> dir,
          "action"   -> action,
          "actorId"  -> actorId,
          "targetId" -> targetId,
        )}"
    )
  }

  def loginAttempts(
    limit: Option[Int] = None,
    outcome: Option[String] = None,
  ): EventStream[Either[ApiError, List[LoginAttemptEntry]]] = {
    HttpClient.get[List[LoginAttemptEntry]](
      s"/api/admin/login-attempts${query("limit" -> limit, "outcome" -> outcome)}"
    )
  }

  def rateLimits: EventStream[Either[ApiError, List[RateLimitEntry]]] = {
    HttpClient.get[List[RateLimitEntry]]("/api/admin/rate-limits")
  }

  /** `None` clears every key. */
  def clearRateLimits(key: Option[String]): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, "/api/admin/rate-limits/clear", Some(ClearRateLimitRequest(key).toJson))
  }

  def systemOverview: EventStream[Either[ApiError, SystemOverview]] = {
    HttpClient.get[SystemOverview]("/api/admin/system")
  }

  def systemPrune: EventStream[Either[ApiError, PruneResult]] = {
    HttpClient.post[PruneResult]("/api/admin/system/prune")
  }

  def wordFormAnomalies: EventStream[Either[ApiError, List[WordFormAnomaly]]] = {
    HttpClient.get[List[WordFormAnomaly]]("/api/admin/word-forms/anomalies")
  }

  def deleteWordFormAnomaly(formWordId: Long, relation: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(
      _.POST,
      "/api/admin/word-forms/anomalies/delete",
      Some(DeleteWordFormRequest(formWordId, relation).toJson),
    )
  }

  def usageRoutes(windowHours: Option[Int] = None): EventStream[Either[ApiError, List[RouteUsage]]] = {
    HttpClient.get[List[RouteUsage]](s"/api/admin/usage/routes${query("windowHours" -> windowHours)}")
  }

  def usageSuspicious(
    windowHours: Option[Int] = None,
    actionThreshold: Option[Int] = None,
    ipThreshold: Option[Int] = None,
  ): EventStream[Either[ApiError, List[SuspiciousUser]]] = {
    HttpClient.get[List[SuspiciousUser]](
      s"/api/admin/usage/suspicious${query("windowHours" -> windowHours, "actionThreshold" -> actionThreshold, "ipThreshold" -> ipThreshold)}"
    )
  }
}
