package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.GroupPaths
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
    HttpClient.call[GroupPage](
      GroupPaths
        .list()
        .withQuery(
          query("page" -> page, "pageSize" -> pageSize, "sort" -> sort, "dir" -> dir, "q" -> search, "tag" -> tag)
        )
    )
  }

  def get(groupId: Long): EventStream[Either[ApiError, GroupDetail]] = {
    HttpClient.call[GroupDetail](GroupPaths.get(groupId))
  }

  /** Creates a group; the caller becomes its sole admin. */
  def create(name: String): EventStream[Either[ApiError, GroupDetail]] = {
    HttpClient.call[GroupDetail](GroupPaths.create(), Some(CreateGroupRequest(name).toJson))
  }

  /** Redeems an invite code, joining as a plain member. */
  def join(code: String): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(GroupPaths.join(), Some(JoinGroupRequest(code).toJson))
  }

  def leave(groupId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(GroupPaths.leave(groupId))
  }

  /** Admin-only. */
  def renameGroup(groupId: Long, name: String): EventStream[Either[ApiError, GroupDetail]] = {
    HttpClient.call[GroupDetail](GroupPaths.renameGroup(groupId), Some(RenameGroupRequest(name).toJson))
  }

  def regenerateInviteCode(groupId: Long): EventStream[Either[ApiError, InviteCodeResponse]] = {
    HttpClient.call[InviteCodeResponse](GroupPaths.regenerateInviteCode(groupId))
  }

  def setMemberRole(groupId: Long, userId: Long, role: GroupRole): EventStream[Either[ApiError, GroupMemberSummary]] = {
    HttpClient
      .call[GroupMemberSummary](GroupPaths.setMemberRole(groupId, userId), Some(SetMemberRoleRequest(role).toJson))
  }

  def removeMember(groupId: Long, userId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(GroupPaths.removeMember(groupId, userId))
  }

  /** Attaches one of the caller's own tags to a group they belong to. */
  def attachTag(groupId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(GroupPaths.attachTag(groupId, tagId))
  }

  def detachTag(groupId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(GroupPaths.detachTag(groupId, tagId))
  }

  /** Admin-only. */
  def deleteGroup(groupId: Long): EventStream[Either[ApiError, Unit]] = {
    HttpClient.callUnit(GroupPaths.deleteGroup(groupId))
  }
}
