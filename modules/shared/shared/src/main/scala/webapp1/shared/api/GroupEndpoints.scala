package webapp1.shared.api

import webapp1.shared.domain.{Group, GroupMember, GroupPair}
import webapp1.shared.dto.{CreateGroupRequest, CreatePairRequest, InviteMemberRequest, UpdateRoleRequest}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, withCodecError}
import ApiSchemas.given

/** Groups, their word pairs, their members and the invitations that create members.
  *
  * Membership and role are enforced by `GroupService`, not by an aspect, so these endpoints answer 403 (not a member /
  * read-only / admin-only) as ordinary failures. The four endpoints that change membership answer a bare 204: the
  * frontend reloads the affected list rather than patching it from a response body.
  *
  * All but [[listGroups]] declare the same six statuses, because every one of them calls a `GroupService` method typed
  * `IO[GroupFailure, ?]` and `ApiFailures.group` maps that enum onto 400/403/404/409. Which *cases* an individual
  * method can actually raise is narrower than that — `getGroup` cannot produce a 409, `createGroup` cannot produce a
  * 404 — but the service signatures do not say so, so the descriptions cannot either. Narrowing them is a change to
  * `GroupService`, not to this file.
  */
object GroupEndpoints {

  private val groupId = PathCodec.long("id")
  private val memberId = PathCodec.long("userId")

  /** See [[AdminEndpoints.deleteUser]] for why an empty 204 is described as a status codec and never as
    * `.out[Unit](Status.NoContent)`.
    */
  private val noContent = HttpCodec.status(Status.NoContent)

  val createGroup = {
    Endpoint(Method.POST / "api" / "groups")
      .in[CreateGroupRequest]
      .withCodecError
      .out[Group](Status.Created)
      .outErrors(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  /** `GroupService.myGroups` is a `UIO`, so nothing but the `authenticated` aspect and a defect can fail this. */
  val listGroups = {
    Endpoint(Method.GET / "api" / "groups").out[List[Group]].outErrors(failure.unauthorized, failure.internalError)
  }

  val getGroup = {
    Endpoint(Method.GET / "api" / "groups" / groupId)
      .out[Group]
      .outErrors(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val deleteGroup = {
    Endpoint(Method.DELETE / "api" / "groups" / groupId)
      .outCodec(noContent)
      .outErrors(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val listPairs = {
    Endpoint(Method.GET / "api" / "groups" / groupId / "pairs")
      .out[List[GroupPair]]
      .outErrors(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val addPair = {
    Endpoint(Method.POST / "api" / "groups" / groupId / "pairs")
      .in[CreatePairRequest]
      .withCodecError
      .out[GroupPair](Status.Created)
      .outErrors(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val listMembers = {
    Endpoint(Method.GET / "api" / "groups" / groupId / "members")
      .out[List[GroupMember]]
      .outErrors(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val removeMember = {
    Endpoint(Method.DELETE / "api" / "groups" / groupId / "members" / memberId)
      .outCodec(noContent)
      .outErrors(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val updateMemberRole = {
    Endpoint(Method.PUT / "api" / "groups" / groupId / "members" / memberId)
      .in[UpdateRoleRequest]
      .withCodecError
      .outCodec(noContent)
      .outErrors(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val inviteMember = {
    Endpoint(Method.POST / "api" / "groups" / groupId / "invitations")
      .in[InviteMemberRequest]
      .withCodecError
      .outCodec(noContent)
      .outErrors(
        failure.badRequest,
        failure.unauthorized,
        failure.forbidden,
        failure.notFound,
        failure.conflict,
        failure.internalError,
      )
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = {
    List(
      createGroup,
      listGroups,
      getGroup,
      deleteGroup,
      listPairs,
      addPair,
      listMembers,
      removeMember,
      updateMemberRole,
      inviteMember,
    )
  }
}
