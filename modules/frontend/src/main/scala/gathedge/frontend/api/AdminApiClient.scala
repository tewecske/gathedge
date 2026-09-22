package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.AdminPaths
import gathedge.shared.domain.{OAuthProvider, User}
import gathedge.shared.dto.{
  AdminUserDetail,
  AuditPage,
  ClearRateLimitRequest,
  CreateUserRequest,
  DeleteWordFormRequest,
  DuplicateGameGroup,
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

import HttpClient.query

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
    HttpClient.call[UserPage](
      AdminPaths
        .listUsers()
        .withQuery(query("page" -> page, "pageSize" -> pageSize, "sort" -> sort, "dir" -> dir, "q" -> search))
    )
  }

  def getUser(id: Long): EventStream[Either[ApiError, User]] = {
    HttpClient.call[User](AdminPaths.getUser(id))
  }

  def createUser(request: CreateUserRequest): EventStream[Either[ApiError, User]] = {
    HttpClient.call[User](AdminPaths.createUser(), Some(request.toJson))
  }

  def updateUser(id: Long, request: UpdateUserRequest): EventStream[Either[ApiError, User]] = {
    HttpClient.call[User](AdminPaths.updateUser(id), Some(request.toJson))
  }

  def deleteUser(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(AdminPaths.deleteUser(id))
  }

  def userDetail(id: Long): EventStream[Either[ApiError, AdminUserDetail]] = {
    HttpClient.call[AdminUserDetail](AdminPaths.userDetail(id))
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
    HttpClient.call[MyPlayPage](
      AdminPaths
        .userPlays(id)
        .withQuery(
          query(
            "gameId"   -> gameId,
            "page"     -> page,
            "pageSize" -> pageSize,
            "sort"     -> sort,
            "dir"      -> dir,
            "q"        -> search,
          )
        )
    )
  }

  /** One of `id`'s finished plays, with its score and full answer history — the admin-scoped counterpart of
    * `GameApiClient.getResults`, which is owner-only.
    */
  def userPlayResults(id: Long, playId: Long): EventStream[Either[ApiError, GameResults]] = {
    HttpClient.call[GameResults](AdminPaths.userPlayResults(id, playId))
  }

  def verifyUserEmail(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(AdminPaths.verifyUserEmail(id))
  }

  def resendUserVerification(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(AdminPaths.resendUserVerification(id))
  }

  def revokeUserSessions(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(AdminPaths.revokeUserSessions(id))
  }

  def unlinkUserIdentity(id: Long, provider: OAuthProvider): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(AdminPaths.unlinkUserIdentity(id, OAuthProvider.wireName(provider)))
  }

  def clearUserLockout(id: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(AdminPaths.clearUserLockout(id))
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
    HttpClient.call[AuditPage](
      AdminPaths
        .auditLog()
        .withQuery(
          query(
            "page"     -> page,
            "pageSize" -> pageSize,
            "sort"     -> sort,
            "dir"      -> dir,
            "action"   -> action,
            "actorId"  -> actorId,
            "targetId" -> targetId,
          )
        )
    )
  }

  def loginAttempts(
    limit: Option[Int] = None,
    outcome: Option[String] = None,
  ): EventStream[Either[ApiError, List[LoginAttemptEntry]]] = {
    HttpClient.call[List[LoginAttemptEntry]](
      AdminPaths.loginAttempts().withQuery(query("limit" -> limit, "outcome" -> outcome))
    )
  }

  def rateLimits: EventStream[Either[ApiError, List[RateLimitEntry]]] = {
    HttpClient.call[List[RateLimitEntry]](AdminPaths.rateLimits())
  }

  /** `None` clears every key. */
  def clearRateLimits(key: Option[String]): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(AdminPaths.clearRateLimits(), Some(ClearRateLimitRequest(key).toJson))
  }

  def systemOverview: EventStream[Either[ApiError, SystemOverview]] = {
    HttpClient.call[SystemOverview](AdminPaths.systemOverview())
  }

  def systemPrune: EventStream[Either[ApiError, PruneResult]] = {
    HttpClient.call[PruneResult](AdminPaths.systemPrune())
  }

  def wordFormAnomalies: EventStream[Either[ApiError, List[WordFormAnomaly]]] = {
    HttpClient.call[List[WordFormAnomaly]](AdminPaths.wordFormAnomalies())
  }

  def deleteWordFormAnomaly(formWordId: Long, relation: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(AdminPaths.deleteWordFormAnomaly(), Some(DeleteWordFormRequest(formWordId, relation).toJson))
  }

  /** Every set of wordlists more than one game was built from — the duplicate report. */
  def duplicateGames: EventStream[Either[ApiError, List[DuplicateGameGroup]]] = {
    HttpClient.call[List[DuplicateGameGroup]](AdminPaths.duplicateGames())
  }

  def usageRoutes(windowHours: Option[Int] = None): EventStream[Either[ApiError, List[RouteUsage]]] = {
    HttpClient.call[List[RouteUsage]](AdminPaths.usageRoutes().withQuery(query("windowHours" -> windowHours)))
  }

  def usageSuspicious(
    windowHours: Option[Int] = None,
    actionThreshold: Option[Int] = None,
    ipThreshold: Option[Int] = None,
  ): EventStream[Either[ApiError, List[SuspiciousUser]]] = {
    HttpClient.call[List[SuspiciousUser]](
      AdminPaths
        .usageSuspicious()
        .withQuery(
          query("windowHours" -> windowHours, "actionThreshold" -> actionThreshold, "ipThreshold" -> ipThreshold)
        )
    )
  }
}
