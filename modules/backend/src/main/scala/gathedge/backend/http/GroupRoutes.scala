package gathedge.backend.http

import gathedge.backend.service.{AuthService, GroupService}
import gathedge.shared.api.GroupEndpoints
import gathedge.shared.domain.User
import gathedge.shared.dto.{
  CreateGroupRequest,
  InviteCodeResponse,
  JoinGroupRequest,
  Paging,
  RenameGroupRequest,
  SetMemberRoleRequest,
  SortDirection,
}
import zio.*
import zio.http.*

/** Shareable tag groups. `list`/`get` sit behind `optionalUser`, the same as `WordRoutes.list`/`.get` — a visitor with
  * no session sees the same groups and detail with no roster/invite-code/role, the way `GroupDetail` already narrows
  * those for a signed-in non-member. Everything else still sits behind `authenticated`. See
  * `shared.api.GroupEndpoints`.
  */
object GroupRoutes {

  private def userId: URIO[User, Long] = ZIO.service[User].map(_.id)

  /** The viewer, when there is one. Supplied by `optionalUser`. */
  private def viewerId: URIO[Option[User], Option[Long]] = ZIO.service[Option[User]].map(_.map(_.id))

  /** An empty `q=`/`tag=` is the search box after it has been cleared, which is not a filter — the same rule
    * `AdminRoutes.searchTerm` follows.
    */
  private def searchTerm(requested: Option[String]): Option[String] = {
    requested.map(_.trim).filter(_.nonEmpty)
  }

  private val listRoute = {
    GroupEndpoints.list.implementHandler(
      handler {
        (
          page: Option[Int],
          pageSize: Option[Int],
          sort: Option[String],
          dir: Option[String],
          q: Option[String],
          tag: Option[String],
        ) =>
          viewerId.flatMap { id =>
            GroupService.listPage(
              id,
              Paging.boundedPage(page),
              Paging.boundedPageSize(pageSize),
              searchTerm(q),
              searchTerm(tag),
              sort,
              SortDirection.isDescending(dir),
            )
          }
      }
    )
  }

  private val getRoute = {
    GroupEndpoints.get.implementHandler(
      handler((groupId: Long) =>
        viewerId.flatMap(viewer => GroupService.detail(groupId, viewer).mapError(ApiFailures.group))
      )
    )
  }

  private val createRoute = {
    GroupEndpoints.create.implementHandler(
      handler { (body: CreateGroupRequest) =>
        userId.flatMap(id => GroupService.create(body.name, id).mapError(ApiFailures.groupCreate))
      }
    )
  }

  private val joinRoute = {
    GroupEndpoints.join.implementHandler(
      handler { (body: JoinGroupRequest) =>
        userId.flatMap(id => GroupService.join(body.code, id).mapError(ApiFailures.groupJoin))
      }
    )
  }

  private val leaveRoute = {
    GroupEndpoints.leave.implementHandler(
      handler((groupId: Long) => userId.flatMap(id => GroupService.leave(groupId, id).mapError(ApiFailures.groupLeave)))
    )
  }

  private val renameGroupRoute = {
    GroupEndpoints.renameGroup.implementHandler(
      handler { (groupId: Long, body: RenameGroupRequest) =>
        userId.flatMap(id => GroupService.renameGroup(groupId, body.name, id).mapError(ApiFailures.groupRename))
      }
    )
  }

  private val regenerateInviteCodeRoute = {
    GroupEndpoints.regenerateInviteCode.implementHandler(
      handler { (groupId: Long) =>
        userId
          .flatMap(id => GroupService.regenerateInviteCode(groupId, id).mapError(ApiFailures.groupAdmin))
          .map(InviteCodeResponse.apply)
      }
    )
  }

  private val setMemberRoleRoute = {
    GroupEndpoints.setMemberRole.implementHandler(
      handler { (groupId: Long, targetUserId: Long, body: SetMemberRoleRequest) =>
        userId.flatMap { actingId =>
          GroupService.setMemberRole(groupId, actingId, targetUserId, body.role).mapError(ApiFailures.groupAdmin)
        }
      }
    )
  }

  private val removeMemberRoute = {
    GroupEndpoints.removeMember.implementHandler(
      handler { (groupId: Long, targetUserId: Long) =>
        userId.flatMap(actingId =>
          GroupService.removeMember(groupId, actingId, targetUserId).mapError(ApiFailures.groupAdmin)
        )
      }
    )
  }

  private val attachTagRoute = {
    GroupEndpoints.attachTag.implementHandler(
      handler { (groupId: Long, tagId: Long) =>
        userId.flatMap(id => GroupService.attachTag(groupId, tagId, id).mapError(ApiFailures.groupAttachTag))
      }
    )
  }

  private val detachTagRoute = {
    GroupEndpoints.detachTag.implementHandler(
      handler { (groupId: Long, tagId: Long) =>
        userId.flatMap(id => GroupService.detachTag(groupId, tagId, id).mapError(ApiFailures.groupDetachTag))
      }
    )
  }

  private val deleteGroupRoute = {
    GroupEndpoints.deleteGroup.implementHandler(
      handler((groupId: Long) =>
        userId.flatMap(id => GroupService.deleteGroup(groupId, id).mapError(ApiFailures.groupAdmin))
      )
    )
  }

  private val publicRoutes = {
    Routes(listRoute, getRoute) @@ RouteSupport.optionalUser
  }

  private val sessionRoutes = {
    Routes(
      createRoute,
      joinRoute,
      leaveRoute,
      renameGroupRoute,
      regenerateInviteCodeRoute,
      setMemberRoleRoute,
      removeMemberRoute,
      attachTagRoute,
      detachTagRoute,
      deleteGroupRoute,
    ) @@ RouteSupport.authenticated @@ RouteSupport.csrf
  }

  val routes: Routes[AuthService & GroupService, Response] = publicRoutes ++ sessionRoutes
}
