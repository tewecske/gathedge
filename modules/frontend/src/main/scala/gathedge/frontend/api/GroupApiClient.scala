package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.domain.{Group, GroupRole}
import gathedge.shared.dto.{
  CreateGroupRequest,
  GroupDetail,
  GroupMemberSummary,
  InviteCodeResponse,
  JoinGroupRequest,
  SetMemberRoleRequest,
}
import zio.json._

/** Shareable tag groups' calls. The shared `GroupEndpoints` description stays the backend's and the OpenAPI document's
  * source of truth, pinned by `ApiPathParitySpec`. Every call needs a session — there is no public half, unlike the
  * vocabulary listing.
  */
object GroupApiClient {

  /** Every group, with the caller's own role in each. */
  def list(): EventStream[Either[ApiError, List[Group]]] = {
    HttpClient.get[List[Group]]("/api/groups")
  }

  def get(groupId: Long): EventStream[Either[ApiError, GroupDetail]] = {
    HttpClient.get[GroupDetail](s"/api/groups/$groupId")
  }

  /** Creates a group; the caller becomes its sole admin. */
  def create(name: String): EventStream[Either[ApiError, GroupDetail]] = {
    HttpClient.post[GroupDetail]("/api/groups", Some(CreateGroupRequest(name).toJson))
  }

  /** Redeems an invite code, joining as a plain member. */
  def join(code: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, "/api/groups/join", Some(JoinGroupRequest(code).toJson))
  }

  def leave(groupId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.POST, s"/api/groups/$groupId/leave")
  }

  def regenerateInviteCode(groupId: Long): EventStream[Either[ApiError, InviteCodeResponse]] = {
    HttpClient.post[InviteCodeResponse](s"/api/groups/$groupId/invite-code/regenerate")
  }

  def setMemberRole(groupId: Long, userId: Long, role: GroupRole): EventStream[Either[ApiError, GroupMemberSummary]] = {
    HttpClient.put[GroupMemberSummary](
      s"/api/groups/$groupId/members/$userId/role",
      Some(SetMemberRoleRequest(role).toJson),
    )
  }

  def removeMember(groupId: Long, userId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/groups/$groupId/members/$userId")
  }

  /** Attaches one of the caller's own tags to a group they belong to. */
  def attachTag(groupId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.PUT, s"/api/groups/$groupId/tags/$tagId")
  }

  def detachTag(groupId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.unit(_.DELETE, s"/api/groups/$groupId/tags/$tagId")
  }
}
