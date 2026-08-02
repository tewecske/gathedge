package webapp1.shared.api

import webapp1.shared.domain.{Group, GroupMember, GroupPair}
import webapp1.shared.dto.{CreateGroupRequest, CreatePairRequest, InviteMemberRequest, UpdateRoleRequest}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.withErrors
import ApiSchemas.given

/** Groups, their word pairs, their members and the invitations that create members.
  *
  * Membership and role are enforced by `GroupService`, not by an aspect, so these endpoints answer 403 (not a member /
  * read-only / admin-only) as ordinary failures. The four endpoints that change membership answer a bare 204: the
  * frontend reloads the affected list rather than patching it from a response body.
  */
object GroupEndpoints {

  private val groupId = PathCodec.long("id")
  private val memberId = PathCodec.long("userId")

  /** See [[AdminEndpoints.deleteUser]] for why an empty 204 is described as a status codec and never as
    * `.out[Unit](Status.NoContent)`.
    */
  private val noContent = HttpCodec.status(Status.NoContent)

  val createGroup = {
    withErrors(Endpoint(Method.POST / "api" / "groups").in[CreateGroupRequest].out[Group](Status.Created))
  }

  val listGroups = {
    withErrors(Endpoint(Method.GET / "api" / "groups").out[List[Group]])
  }

  val getGroup = {
    withErrors(Endpoint(Method.GET / "api" / "groups" / groupId).out[Group])
  }

  val deleteGroup = {
    withErrors(Endpoint(Method.DELETE / "api" / "groups" / groupId).outCodec(noContent))
  }

  val listPairs = {
    withErrors(Endpoint(Method.GET / "api" / "groups" / groupId / "pairs").out[List[GroupPair]])
  }

  val addPair = {
    withErrors(
      Endpoint(Method.POST / "api" / "groups" / groupId / "pairs").in[CreatePairRequest].out[GroupPair](Status.Created)
    )
  }

  val listMembers = {
    withErrors(Endpoint(Method.GET / "api" / "groups" / groupId / "members").out[List[GroupMember]])
  }

  val removeMember = {
    withErrors(Endpoint(Method.DELETE / "api" / "groups" / groupId / "members" / memberId).outCodec(noContent))
  }

  val updateMemberRole = {
    withErrors(
      Endpoint(Method.PUT / "api" / "groups" / groupId / "members" / memberId).in[UpdateRoleRequest].outCodec(noContent)
    )
  }

  val inviteMember = {
    withErrors(
      Endpoint(Method.POST / "api" / "groups" / groupId / "invitations").in[InviteMemberRequest].outCodec(noContent)
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
