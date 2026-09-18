package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.domain.GroupRole
import gathedge.shared.dto.{
  CreateGroupRequest,
  GroupDetail,
  GroupMemberSummary,
  GroupPage,
  InviteCodeResponse,
  JoinGroupRequest,
  RenameGroupRequest,
  SetMemberRoleRequest,
}
import zio.json._

import HttpClient.query

/** Shareable tag groups' calls, over [[HttpClient]] the same way [[WordApiClient]] is. [[listPage]]/[[get]] answer
  * without a session, the same as `WordApiClient.list`/`.get`; everything else needs one.
  */
object GroupApiClient {

  /** One page of groups, with the caller's own role in each. Every parameter is optional, the same as
    * `AdminApiClient.listUsers` — omitting all of them is the first page of everything, in the listing's own order.
    */
  def listPage(
    page: Option[Int] = None,
    pageSize: Option[Int] = None,
    sort: Option[String] = None,
    dir: Option[String] = None,
    search: Option[String] = None,
    tag: Option[String] = None,
  ): EventStream[Either[ApiError, GroupPage]] = {
    HttpClient.get[GroupPage](
      s"/api/groups${query("page" -> page, "pageSize" -> pageSize, "sort" -> sort, "dir" -> dir, "q" -> search, "tag" -> tag)}"
    )
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

  /** Admin-only. */
  def renameGroup(groupId: Long, name: String): EventStream[Either[ApiError, GroupDetail]] = {
    HttpClient.put[GroupDetail](s"/api/groups/$groupId", Some(RenameGroupRequest(name).toJson))
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
