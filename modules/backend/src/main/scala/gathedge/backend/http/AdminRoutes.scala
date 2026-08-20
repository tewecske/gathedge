package gathedge.backend.http

import gathedge.backend.config.AppConfig
import gathedge.backend.service.{AdminActor, AdminFailure, AdminService, AuthService, SystemService, UsageStatsService}
import gathedge.shared.api.AdminEndpoints
import gathedge.shared.domain.{OAuthProvider, User}
import gathedge.shared.dto.{
  ClearRateLimitRequest,
  CreateUserRequest,
  DeleteWordFormRequest,
  Paging,
  SortDirection,
  UpdateUserRequest,
}
import zio.*
import zio.http.*

/** Administrator user management, account diagnostics, the audit trail and the system overview.
  *
  * Like every other resource, this is implemented against the descriptions in `shared` (`AdminEndpoints`): a handler
  * here is a plain function from the endpoint's input type to its output type, so there is no JSON reading or writing
  * and no way to answer with a body or a status the description doesn't allow. The cross-cutting checks are what stays
  * in this file — `adminOnly`, `requestContext` and `csrf` are `HandlerAspect`s applied to the whole `Routes` value.
  *
  * Every mutating handler builds an [[AdminActor]] from the two contexts the aspects supply: the acting administrator
  * from `adminOnly`, and the peer address from `requestContext`. Both end up on the `audit_log` row, which is the point
  * of carrying the address at all — an audit trail that cannot say where an action came from answers half the question.
  */
object AdminRoutes {

  /** The acting administrator plus where they are acting from, out of the two context-providing aspects below. */
  private def actor: URIO[User & RouteSupport.RequestContext, AdminActor] = {
    for {
      user    <- ZIO.service[User]
      context <- ZIO.service[RouteSupport.RequestContext]
    } yield AdminActor(user.id, context.clientIp)
  }

  /** How many rows the un-paged list endpoint returns when the caller names no limit, and the most it will return
    * however large a `limit` it is asked for. The cap is the real protection: the parameter reaches the SQL `LIMIT`
    * directly. The two *paged* listings are bounded by `Paging` instead, which is shared with the browser so the
    * dropdown cannot offer a size the server would clamp.
    */
  private val defaultLimit = 50
  private val maxLimit     = 500

  private def boundedLimit(requested: Option[Int]): Int = {
    requested.getOrElse(defaultLimit).max(1).min(maxLimit)
  }

  /** An empty `q=` is the search box after the administrator has cleared it, which is not a filter. */
  private def searchTerm(requested: Option[String]): Option[String] = {
    requested.map(_.trim).filter(_.nonEmpty)
  }

  private val listUsersRoute = {
    AdminEndpoints.listUsers
      .implementHandler(
        handler {
          (page: Option[Int], pageSize: Option[Int], sort: Option[String], dir: Option[String], q: Option[String]) =>
            AdminService.listUsers(
              Paging.boundedPage(page),
              Paging.boundedPageSize(pageSize),
              searchTerm(q),
              sort,
              SortDirection.isDescending(dir),
            )
        }
      )
  }

  private val getUserRoute = {
    AdminEndpoints.getUser
      .implementHandler(handler((id: Long) => AdminService.getUser(id).mapError(ApiFailures.admin)))
  }

  private val createUserRoute = {
    AdminEndpoints.createUser
      .implementHandler(
        handler { (body: CreateUserRequest) =>
          actor.flatMap { acting =>
            AdminService
              .createUser(acting, body.email, body.password, body.isAdmin)
              .mapError(ApiFailures.admin)
          }
        }
      )
  }

  private val updateUserRoute = {
    AdminEndpoints.updateUser
      .implementHandler(
        handler { (id: Long, body: UpdateUserRequest) =>
          actor.flatMap { acting =>
            AdminService
              .updateUser(acting, id, body.email, body.isAdmin, body.password)
              .mapError(ApiFailures.admin)
          }
        }
      )
  }

  private val deleteUserRoute = {
    AdminEndpoints.deleteUser
      .implementHandler(
        handler((id: Long) => actor.flatMap(acting => AdminService.deleteUser(acting, id).mapError(ApiFailures.admin)))
      )
  }

  private val userDetailRoute = {
    AdminEndpoints.userDetail
      .implementHandler(handler((id: Long) => AdminService.userDetail(id).mapError(ApiFailures.admin)))
  }

  private val userPlaysRoute = {
    AdminEndpoints.userPlays
      .implementHandler(
        handler {
          (
            id: Long,
            gameId: Option[Long],
            page: Option[Int],
            pageSize: Option[Int],
            sort: Option[String],
            dir: Option[String],
          ) =>
            AdminService
              .userPlays(
                id,
                gameId,
                Paging.boundedPage(page),
                Paging.boundedPageSize(pageSize),
                sort,
                SortDirection.isDescending(dir),
              )
              .mapError(ApiFailures.admin)
        }
      )
  }

  private val verifyUserEmailRoute = {
    AdminEndpoints.verifyUserEmail
      .implementHandler(
        handler { (id: Long) =>
          actor.flatMap(acting => AdminService.verifyEmailFor(acting, id).mapError(ApiFailures.admin))
        }
      )
  }

  private val resendUserVerificationRoute = {
    AdminEndpoints.resendUserVerification
      .implementHandler(
        handler { (id: Long) =>
          actor.flatMap(acting => AdminService.resendVerificationFor(acting, id).mapError(ApiFailures.admin))
        }
      )
  }

  private val revokeUserSessionsRoute = {
    AdminEndpoints.revokeUserSessions
      .implementHandler(
        handler { (id: Long) =>
          actor.flatMap(acting => AdminService.revokeSessions(acting, id).mapError(ApiFailures.admin))
        }
      )
  }

  private val unlinkUserIdentityRoute = {
    AdminEndpoints.unlinkUserIdentity
      .implementHandler(
        handler { (id: Long, provider: String) =>
          // An unparseable provider segment names a provider this build has never heard of, which is a resource that
          // does not exist rather than a malformed request — the same answer `/api/auth/{provider}/start` gives.
          OAuthProvider.fromString(provider) match {
            case None       =>
              ZIO.fail(ApiFailures.admin(AdminFailure.NotFound))
            case Some(kind) =>
              actor.flatMap(acting => AdminService.unlinkIdentity(acting, id, kind).mapError(ApiFailures.admin))
          }
        }
      )
  }

  private val clearUserLockoutRoute = {
    AdminEndpoints.clearUserLockout
      .implementHandler(
        handler { (id: Long) =>
          actor.flatMap(acting => AdminService.clearLockout(acting, id).mapError(ApiFailures.admin))
        }
      )
  }

  private val auditLogRoute = {
    AdminEndpoints.auditLog
      .implementHandler(
        handler {
          (
            page: Option[Int],
            pageSize: Option[Int],
            sort: Option[String],
            dir: Option[String],
            action: Option[String],
            actorId: Option[Long],
            targetId: Option[String],
          ) =>
            AdminService.auditLog(
              Paging.boundedPage(page),
              Paging.boundedPageSize(pageSize),
              sort,
              SortDirection.isDescending(dir),
              action,
              actorId,
              targetId,
            )
        }
      )
  }

  private val loginAttemptsRoute = {
    AdminEndpoints.loginAttempts
      .implementHandler(
        handler { (limit: Option[Int], outcome: Option[String]) =>
          AdminService.loginAttempts(boundedLimit(limit), outcome)
        }
      )
  }

  private val rateLimitsRoute = {
    AdminEndpoints.rateLimits.implementHandler(handler((_: Unit) => AdminService.rateLimits))
  }

  private val clearRateLimitsRoute = {
    AdminEndpoints.clearRateLimits
      .implementHandler(
        handler { (body: ClearRateLimitRequest) =>
          actor.flatMap(acting => AdminService.clearRateLimits(acting, body.key.filter(_.trim.nonEmpty)))
        }
      )
  }

  private val systemOverviewRoute = {
    AdminEndpoints.systemOverview.implementHandler(handler((_: Unit) => SystemService.overview))
  }

  private val systemPruneRoute = {
    AdminEndpoints.systemPrune
      .implementHandler(handler((_: Unit) => actor.flatMap(acting => SystemService.prune(acting))))
  }

  private val wordFormAnomaliesRoute = {
    AdminEndpoints.wordFormAnomalies.implementHandler(handler((_: Unit) => AdminService.wordFormAnomalies))
  }

  private val deleteWordFormAnomalyRoute = {
    AdminEndpoints.deleteWordFormAnomaly
      .implementHandler(
        handler { (body: DeleteWordFormRequest) =>
          actor.flatMap(acting => AdminService.deleteWordFormAnomaly(acting, body))
        }
      )
  }

  private val usageRoutesRoute = {
    AdminEndpoints.usageRoutes
      .implementHandler(
        handler((windowHours: Option[Int]) =>
          UsageStatsService.topRoutes(windowHours.getOrElse(UsageStatsService.defaultWindowHours))
        )
      )
  }

  private val usageSuspiciousRoute = {
    AdminEndpoints.usageSuspicious
      .implementHandler(
        handler { (windowHours: Option[Int], actionThreshold: Option[Int], ipThreshold: Option[Int]) =>
          UsageStatsService.suspiciousUsers(
            windowHours.getOrElse(UsageStatsService.defaultWindowHours),
            actionThreshold.getOrElse(UsageStatsService.defaultActionThreshold),
            ipThreshold.getOrElse(UsageStatsService.defaultIpThreshold),
          )
        }
      )
  }

  // `AppConfig` is here for `requestContext`, which needs the trusted-proxy hop count to decide what the client's
  // address is — the value that lands on every `audit_log` row this file writes.
  val routes: Routes[AuthService & AdminService & SystemService & UsageStatsService & AppConfig, Response] = {
    Routes(
      listUsersRoute,
      getUserRoute,
      createUserRoute,
      updateUserRoute,
      deleteUserRoute,
      userDetailRoute,
      userPlaysRoute,
      verifyUserEmailRoute,
      resendUserVerificationRoute,
      revokeUserSessionsRoute,
      unlinkUserIdentityRoute,
      clearUserLockoutRoute,
      auditLogRoute,
      loginAttemptsRoute,
      rateLimitsRoute,
      clearRateLimitsRoute,
      systemOverviewRoute,
      systemPruneRoute,
      wordFormAnomaliesRoute,
      deleteWordFormAnomalyRoute,
      usageRoutesRoute,
      usageSuspiciousRoute,
    ) @@ RouteSupport.adminOnly @@ RouteSupport.requestContext @@ RouteSupport.csrf
  }
}
