package webapp1.backend.http

import webapp1.backend.service.{AuthService, GroupService}
import webapp1.shared.domain.User
import webapp1.shared.dto.{CreateGroupRequest, CreatePairRequest, InviteMemberRequest, UpdateRoleRequest}
import zio.*
import zio.http.*

import JsonSupport.*

object GroupRoutes {

  private val createGroupRoute = {
    Method.POST / "api" / "groups" ->
      handler { (request: Request) =>
        for {
          user <- ZIO.service[User]
          body <- readJson[CreateGroupRequest](request)
          groupService <- ZIO.service[GroupService]
          group <- groupService.createGroup(user.id, body.name).mapError(FailureResponses.group)
        } yield jsonResponse(Status.Created, group)
      }
  }

  private val listGroupsRoute = {
    Method.GET / "api" / "groups" ->
      handler { (_: Request) =>
        for {
          user <- ZIO.service[User]
          groupService <- ZIO.service[GroupService]
          groups <- groupService.myGroups(user.id)
        } yield jsonResponse(Status.Ok, groups)
      }
  }

  private val getGroupRoute = {
    Method.GET / "api" / "groups" / long("id") ->
      handler { (groupId: Long, _: Request) =>
        for {
          user <- ZIO.service[User]
          groupService <- ZIO.service[GroupService]
          group <- groupService.getGroup(user.id, groupId).mapError(FailureResponses.group)
        } yield jsonResponse(Status.Ok, group)
      }
  }

  private val deleteGroupRoute = {
    Method.DELETE / "api" / "groups" / long("id") ->
      handler { (groupId: Long, _: Request) =>
        for {
          user <- ZIO.service[User]
          groupService <- ZIO.service[GroupService]
          _ <- groupService.deleteGroup(user.id, groupId).mapError(FailureResponses.group)
        } yield Response(status = Status.NoContent)
      }
  }

  private val listPairsRoute = {
    Method.GET / "api" / "groups" / long("id") / "pairs" ->
      handler { (groupId: Long, _: Request) =>
        for {
          user <- ZIO.service[User]
          groupService <- ZIO.service[GroupService]
          pairs <- groupService.listPairs(user.id, groupId).mapError(FailureResponses.group)
        } yield jsonResponse(Status.Ok, pairs)
      }
  }

  private val addPairRoute = {
    Method.POST / "api" / "groups" / long("id") / "pairs" ->
      handler { (groupId: Long, request: Request) =>
        for {
          user <- ZIO.service[User]
          body <- readJson[CreatePairRequest](request)
          groupService <- ZIO.service[GroupService]
          pair <- groupService
            .addPair(user.id, user.email, groupId, body.source, body.target)
            .mapError(FailureResponses.group)
        } yield jsonResponse(Status.Created, pair)
      }
  }

  private val listMembersRoute = {
    Method.GET / "api" / "groups" / long("id") / "members" ->
      handler { (groupId: Long, _: Request) =>
        for {
          user <- ZIO.service[User]
          groupService <- ZIO.service[GroupService]
          members <- groupService.listMembers(user.id, groupId).mapError(FailureResponses.group)
        } yield jsonResponse(Status.Ok, members)
      }
  }

  private val removeMemberRoute = {
    Method.DELETE / "api" / "groups" / long("id") / "members" / long("userId") ->
      handler { (groupId: Long, targetUserId: Long, _: Request) =>
        for {
          user <- ZIO.service[User]
          groupService <- ZIO.service[GroupService]
          _ <- groupService.removeMember(user.id, groupId, targetUserId).mapError(FailureResponses.group)
        } yield Response(status = Status.NoContent)
      }
  }

  private val updateRoleRoute = {
    Method.PUT / "api" / "groups" / long("id") / "members" / long("userId") ->
      handler { (groupId: Long, targetUserId: Long, request: Request) =>
        for {
          user <- ZIO.service[User]
          body <- readJson[UpdateRoleRequest](request)
          groupService <- ZIO.service[GroupService]
          _ <- groupService.updateMemberRole(user.id, groupId, targetUserId, body.role).mapError(FailureResponses.group)
        } yield Response(status = Status.NoContent)
      }
  }

  private val inviteMemberRoute = {
    Method.POST / "api" / "groups" / long("id") / "invitations" ->
      handler { (groupId: Long, request: Request) =>
        for {
          user <- ZIO.service[User]
          body <- readJson[InviteMemberRequest](request)
          groupService <- ZIO.service[GroupService]
          _ <- groupService.inviteMember(user.id, groupId, body.email, body.role).mapError(FailureResponses.group)
        } yield Response(status = Status.NoContent)
      }
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
