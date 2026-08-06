package webapp1.backend.http

import webapp1.backend.service.{AuthService, GroupService}
import webapp1.shared.api.GroupEndpoints
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateGroupRequest, CreatePairRequest, InviteMemberRequest, UpdateRoleRequest}
import zio.*
import zio.http.*

object GroupRoutes {

  private val createGroupRoute = {
    GroupEndpoints.createGroup
      .implementHandler(
        handler { (body: CreateGroupRequest) =>
          withContext { (user: User) =>
            GroupService.createGroup(user.id, body.name).mapError(ApiFailures.group)
          }
        }
      )
  }

  private val listGroupsRoute = {
    GroupEndpoints.listGroups
      .implementHandler(handler((_: Unit) => withContext((user: User) => GroupService.myGroups(user.id))))
  }

  private val getGroupRoute = {
    GroupEndpoints.getGroup
      .implementHandler(
        handler { (groupId: Long) =>
          withContext { (user: User) =>
            GroupService.getGroup(user.id, groupId).mapError(ApiFailures.group)
          }
        }
      )
  }

  private val deleteGroupRoute = {
    GroupEndpoints.deleteGroup
      .implementHandler(
        handler { (groupId: Long) =>
          withContext { (user: User) =>
            GroupService.deleteGroup(user.id, groupId).mapError(ApiFailures.group)
          }
        }
      )
  }

  private val listPairsRoute = {
    GroupEndpoints.listPairs
      .implementHandler(
        handler { (groupId: Long) =>
          withContext { (user: User) =>
            GroupService.listPairs(user.id, groupId).mapError(ApiFailures.group)
          }
        }
      )
  }

  private val addPairRoute = {
    GroupEndpoints.addPair
      .implementHandler(
        handler { (groupId: Long, body: CreatePairRequest) =>
          withContext { (user: User) =>
            GroupService
              .addPair(user.id, user.email, groupId, body.source, body.target)
              .mapError(ApiFailures.group)
          }
        }
      )
  }

  private val listMembersRoute = {
    GroupEndpoints.listMembers
      .implementHandler(
        handler { (groupId: Long) =>
          withContext { (user: User) =>
            GroupService.listMembers(user.id, groupId).mapError(ApiFailures.group)
          }
        }
      )
  }

  private val removeMemberRoute = {
    GroupEndpoints.removeMember
      .implementHandler(
        handler { (groupId: Long, targetUserId: Long) =>
          withContext { (user: User) =>
            GroupService.removeMember(user.id, groupId, targetUserId).mapError(ApiFailures.group)
          }
        }
      )
  }

  private val updateRoleRoute = {
    GroupEndpoints.updateMemberRole
      .implementHandler(
        handler { (groupId: Long, targetUserId: Long, body: UpdateRoleRequest) =>
          withContext { (user: User) =>
            GroupService.updateMemberRole(user.id, groupId, targetUserId, body.role).mapError(ApiFailures.group)
          }
        }
      )
  }

  private val inviteMemberRoute = {
    GroupEndpoints.inviteMember
      .implementHandler(
        handler { (groupId: Long, body: InviteMemberRequest) =>
          withContext { (user: User) =>
            GroupService.inviteMember(user.id, groupId, body.email, body.role).mapError(ApiFailures.group)
          }
        }
      )
  }

  val routes: Routes[AuthService & GroupService, Response] = {
    Routes(
      createGroupRoute,
      listGroupsRoute,
      getGroupRoute,
      deleteGroupRoute,
      listPairsRoute,
      addPairRoute,
      listMembersRoute,
      removeMemberRoute,
      updateRoleRoute,
      inviteMemberRoute,
    ) @@ RouteSupport.authenticated @@ RouteSupport.csrf
  }
}
