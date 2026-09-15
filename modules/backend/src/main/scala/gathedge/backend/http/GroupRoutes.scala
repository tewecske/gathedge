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

/** Shareable tag groups. Every operation sits behind `authenticated` — a group is visible to every *account*, not to
  * the open internet, the same as `WordRoutes.listTags` — so there is no public/session split the way `WordRoutes`
  * itself has. See `shared.api.GroupEndpoints`.
  */
object GroupRoutes {

  private def userId: URIO[User, Long] = ZIO.service[User].map(_.id)

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
          userId.flatMap { id =>
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
        userId.flatMap(viewer => GroupService.detail(groupId, viewer).mapError(ApiFailures.group))
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

  val routes: Routes[AuthService & GroupService, Response] = {
    Routes(
      listRoute,
      getRoute,
      createRoute,
      joinRoute,
      leaveRoute,
      renameGroupRoute,
      regenerateInviteCodeRoute,
      setMemberRoleRoute,
      removeMemberRoute,
      attachTagRoute,
      detachTagRoute,
    ) @@ RouteSupport.authenticated @@ RouteSupport.csrf
  }
}
