package webapp1.backend.http

import webapp1.backend.service.{AuthService, GroupFailure, GroupService}
import webapp1.shared.dto.{CreateGroupRequest, CreatePairRequest, InviteMemberRequest, UpdateRoleRequest}
import zio.*
import zio.http.*

import JsonSupport.*
import RouteSupport.{authenticatedUser, csrfCheck}

object GroupRoutes {

  private def groupFailureResponse(failure: GroupFailure): Response = {
    failure match {
      case GroupFailure.ValidationError(fieldErrors) =>
        errorResponse(Status.BadRequest, "Validation failed", fieldErrors)
      case GroupFailure.NotFound =>
        errorResponse(Status.NotFound, "Group not found")
      case GroupFailure.NotMember =>
        errorResponse(Status.Forbidden, "You are not a member of this group")
      case GroupFailure.ReadOnlyMember =>
        errorResponse(Status.Forbidden, "Your role in this group is read-only")
      case GroupFailure.AdminOnly =>
        errorResponse(Status.Forbidden, "Only a group administrator can do this")
      case GroupFailure.LastAdmin =>
        errorResponse(
          Status.Conflict,
          "A group must always have at least one administrator; promote another member first",
        )
      case GroupFailure.InvitationInvalid =>
        errorResponse(Status.BadRequest, "This invitation is invalid, expired, or already used")
    }
  }

  private val createGroupRoute = {
    Method.POST / "api" / "groups" ->
      handler { (request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            user <- authenticatedUser(request)
            body <- readJson[CreateGroupRequest](request)
            groupService <- ZIO.service[GroupService]
            group <- groupService.createGroup(user.id, body.name).mapError(groupFailureResponse)
          } yield jsonResponse(Status.Created, group)
        ).merge
      }
  }

  private val listGroupsRoute = {
    Method.GET / "api" / "groups" ->
      handler { (request: Request) =>
        (
          for {
            user <- authenticatedUser(request)
            groupService <- ZIO.service[GroupService]
            groups <- groupService.myGroups(user.id)
          } yield jsonResponse(Status.Ok, groups)
        ).merge
      }
  }

  private val getGroupRoute = {
    Method.GET / "api" / "groups" / long("id") ->
      handler { (groupId: Long, request: Request) =>
        (
          for {
            user <- authenticatedUser(request)
            groupService <- ZIO.service[GroupService]
            group <- groupService.getGroup(user.id, groupId).mapError(groupFailureResponse)
          } yield jsonResponse(Status.Ok, group)
        ).merge
      }
  }

  private val deleteGroupRoute = {
    Method.DELETE / "api" / "groups" / long("id") ->
      handler { (groupId: Long, request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            user <- authenticatedUser(request)
            groupService <- ZIO.service[GroupService]
            _ <- groupService.deleteGroup(user.id, groupId).mapError(groupFailureResponse)
          } yield Response(status = Status.NoContent)
        ).merge
      }
  }

  private val listPairsRoute = {
    Method.GET / "api" / "groups" / long("id") / "pairs" ->
      handler { (groupId: Long, request: Request) =>
        (
          for {
            user <- authenticatedUser(request)
            groupService <- ZIO.service[GroupService]
            pairs <- groupService.listPairs(user.id, groupId).mapError(groupFailureResponse)
          } yield jsonResponse(Status.Ok, pairs)
        ).merge
      }
  }

  private val addPairRoute = {
    Method.POST / "api" / "groups" / long("id") / "pairs" ->
      handler { (groupId: Long, request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            user <- authenticatedUser(request)
            body <- readJson[CreatePairRequest](request)
            groupService <- ZIO.service[GroupService]
            pair <- groupService
              .addPair(user.id, user.email, groupId, body.source, body.target)
              .mapError(groupFailureResponse)
          } yield jsonResponse(Status.Created, pair)
        ).merge
      }
  }

  private val listMembersRoute = {
    Method.GET / "api" / "groups" / long("id") / "members" ->
      handler { (groupId: Long, request: Request) =>
        (
          for {
            user <- authenticatedUser(request)
            groupService <- ZIO.service[GroupService]
            members <- groupService.listMembers(user.id, groupId).mapError(groupFailureResponse)
          } yield jsonResponse(Status.Ok, members)
        ).merge
      }
  }

  private val removeMemberRoute = {
    Method.DELETE / "api" / "groups" / long("id") / "members" / long("userId") ->
      handler { (groupId: Long, targetUserId: Long, request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            user <- authenticatedUser(request)
            groupService <- ZIO.service[GroupService]
            _ <- groupService.removeMember(user.id, groupId, targetUserId).mapError(groupFailureResponse)
          } yield Response(status = Status.NoContent)
        ).merge
      }
  }

  private val updateRoleRoute = {
    Method.PUT / "api" / "groups" / long("id") / "members" / long("userId") ->
      handler { (groupId: Long, targetUserId: Long, request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            user <- authenticatedUser(request)
            body <- readJson[UpdateRoleRequest](request)
            groupService <- ZIO.service[GroupService]
            _ <- groupService.updateMemberRole(user.id, groupId, targetUserId, body.role).mapError(groupFailureResponse)
          } yield Response(status = Status.NoContent)
        ).merge
      }
  }

  private val inviteMemberRoute = {
    Method.POST / "api" / "groups" / long("id") / "invitations" ->
      handler { (groupId: Long, request: Request) =>
        (
          for {
            _ <- csrfCheck(request)
            user <- authenticatedUser(request)
            body <- readJson[InviteMemberRequest](request)
            groupService <- ZIO.service[GroupService]
            _ <- groupService.inviteMember(user.id, groupId, body.email, body.role).mapError(groupFailureResponse)
          } yield Response(status = Status.NoContent)
        ).merge
      }
  }

  val routes: Routes[AuthService & GroupService, Nothing] = Routes(
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
  )
}
