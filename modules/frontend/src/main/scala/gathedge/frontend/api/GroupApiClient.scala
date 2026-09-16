package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.GroupEndpoints
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

import EndpointClient.{executor, run}

/** Shareable tag groups' calls, generated from `GroupEndpoints` the same way [[WordApiClient]] is from `WordEndpoints`.
  * [[listPage]]/[[get]] answer without a session, the same as `WordApiClient.list`/`.get`; everything else needs one.
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
    run(executor(GroupEndpoints.list(page, pageSize, sort, dir, search, tag)))
  }

  def get(groupId: Long): EventStream[Either[ApiError, GroupDetail]] = {
    run(executor(GroupEndpoints.get(groupId)))
  }

  /** Creates a group; the caller becomes its sole admin. */
  def create(name: String): EventStream[Either[ApiError, GroupDetail]] = {
    run(executor(GroupEndpoints.create(CreateGroupRequest(name))))
  }

  /** Redeems an invite code, joining as a plain member. */
  def join(code: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(GroupEndpoints.join(JoinGroupRequest(code))))
  }

  def leave(groupId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(GroupEndpoints.leave(groupId)))
  }

  /** Admin-only. */
  def renameGroup(groupId: Long, name: String): EventStream[Either[ApiError, GroupDetail]] = {
    run(executor(GroupEndpoints.renameGroup(groupId, RenameGroupRequest(name))))
  }

  def regenerateInviteCode(groupId: Long): EventStream[Either[ApiError, InviteCodeResponse]] = {
    run(executor(GroupEndpoints.regenerateInviteCode(groupId)))
  }

  def setMemberRole(groupId: Long, userId: Long, role: GroupRole): EventStream[Either[ApiError, GroupMemberSummary]] = {
    run(executor(GroupEndpoints.setMemberRole(groupId, userId, SetMemberRoleRequest(role))))
  }

  def removeMember(groupId: Long, userId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(GroupEndpoints.removeMember(groupId, userId)))
  }

  /** Attaches one of the caller's own tags to a group they belong to. */
  def attachTag(groupId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(GroupEndpoints.attachTag(groupId, tagId)))
  }

  def detachTag(groupId: Long, tagId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(GroupEndpoints.detachTag(groupId, tagId)))
  }
}
