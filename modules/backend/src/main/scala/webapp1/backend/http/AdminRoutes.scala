package webapp1.backend.http

import webapp1.backend.service.{AdminActor, AdminFailure, AdminService, AuthService, SystemService}
import webapp1.shared.api.AdminEndpoints
import webapp1.shared.domain.{OAuthProvider, User}
import webapp1.shared.dto.{ClearRateLimitRequest, CreateUserRequest, UpdateUserRequest}
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

  /** How many rows a list endpoint returns when the caller names no limit, and the most it will return however large a
    * `limit` it is asked for. The cap is the real protection: the parameter reaches the SQL `LIMIT` directly.
    */
  private val defaultLimit = 50
  private val maxLimit     = 500

  private def boundedLimit(requested: Option[Int]): Int = {
    requested.getOrElse(defaultLimit).max(1).min(maxLimit)
  }

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
            limit: Option[Int],
            before: Option[Long],
            action: Option[String],
            actorId: Option[Long],
            targetId: Option[String],
          ) =>
            AdminService.auditLog(boundedLimit(limit), before, action, actorId, targetId)
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

  val routes: Routes[AuthService & AdminService & SystemService, Response] = {
    Routes(
      listUsersRoute,
      getUserRoute,
      createUserRoute,
      updateUserRoute,
      deleteUserRoute,
      userDetailRoute,
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
    ) @@ RouteSupport.adminOnly @@ RouteSupport.requestContext @@ RouteSupport.csrf
  }
}
