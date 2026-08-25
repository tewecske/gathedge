package gathedge.frontend.api

import com.raquo.laminar.api.L._
import gathedge.shared.api.GroupEndpoints
import gathedge.shared.domain.{Group, GroupRole}
import gathedge.shared.dto.{
  CreateGroupRequest,
  GroupDetail,
  GroupMemberSummary,
  InviteCodeResponse,
  JoinGroupRequest,
  SetMemberRoleRequest,
}

import EndpointClient.{executor, run}

/** Shareable tag groups' calls, generated from `GroupEndpoints` the same way [[WordApiClient]] is from `WordEndpoints`.
  * Every call needs a session — there is no public half, unlike the vocabulary listing.
  */
object GroupApiClient {

  /** Every group, with the caller's own role in each. */
  def list(): EventStream[Either[ApiError, List[Group]]] = {
    run(executor(GroupEndpoints.list(())))
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
