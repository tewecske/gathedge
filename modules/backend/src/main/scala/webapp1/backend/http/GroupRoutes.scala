package webapp1.backend.http

import webapp1.backend.service.{AuthService, GroupService}
import webapp1.shared.api.GroupEndpoints
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateGroupRequest, CreatePairRequest, InviteMemberRequest, UpdateRoleRequest}
import zio.*
import zio.http.*

object GroupRoutes {

  private val createGroupRoute = {
    GroupEndpoints
      .createGroup
      .implementHandler(
        handler { (body: CreateGroupRequest) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            group <- groupService.createGroup(user.id, body.name).mapError(ApiFailures.group)
          } yield group
        }
      )
  }

  private val listGroupsRoute = {
    GroupEndpoints
      .listGroups
      .implementHandler(
        handler { (_: Unit) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            groups <- groupService.myGroups(user.id)
          } yield groups
        }
      )
  }

  private val getGroupRoute = {
    GroupEndpoints
      .getGroup
      .implementHandler(
        handler { (groupId: Long) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            group <- groupService.getGroup(user.id, groupId).mapError(ApiFailures.group)
          } yield group
        }
      )
  }

  private val deleteGroupRoute = {
    GroupEndpoints
      .deleteGroup
      .implementHandler(
        handler { (groupId: Long) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            _ <- groupService.deleteGroup(user.id, groupId).mapError(ApiFailures.group)
          } yield ()
        }
      )
  }

  private val listPairsRoute = {
    GroupEndpoints
      .listPairs
      .implementHandler(
        handler { (groupId: Long) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            pairs <- groupService.listPairs(user.id, groupId).mapError(ApiFailures.group)
          } yield pairs
        }
      )
  }

  private val addPairRoute = {
    GroupEndpoints
      .addPair
      .implementHandler(
        handler { (groupId: Long, body: CreatePairRequest) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            pair <- groupService
              .addPair(user.id, user.email, groupId, body.source, body.target)
              .mapError(ApiFailures.group)
          } yield pair
        }
      )
  }

  private val listMembersRoute = {
    GroupEndpoints
      .listMembers
      .implementHandler(
        handler { (groupId: Long) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            members <- groupService.listMembers(user.id, groupId).mapError(ApiFailures.group)
          } yield members
        }
      )
  }

  private val removeMemberRoute = {
    GroupEndpoints
      .removeMember
      .implementHandler(
        handler { (groupId: Long, targetUserId: Long) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            _ <- groupService.removeMember(user.id, groupId, targetUserId).mapError(ApiFailures.group)
          } yield ()
        }
      )
  }

  private val updateRoleRoute = {
    GroupEndpoints
      .updateMemberRole
      .implementHandler(
        handler { (groupId: Long, targetUserId: Long, body: UpdateRoleRequest) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            _ <- groupService.updateMemberRole(user.id, groupId, targetUserId, body.role).mapError(ApiFailures.group)
          } yield ()
        }
      )
  }

  private val inviteMemberRoute = {
    GroupEndpoints
      .inviteMember
      .implementHandler(
        handler { (groupId: Long, body: InviteMemberRequest) =>
          for {
            user <- ZIO.service[User]
            groupService <- ZIO.service[GroupService]
            _ <- groupService.inviteMember(user.id, groupId, body.email, body.role).mapError(ApiFailures.group)
          } yield ()
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
