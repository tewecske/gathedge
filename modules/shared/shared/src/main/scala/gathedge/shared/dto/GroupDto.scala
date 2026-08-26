package gathedge.shared.dto

import gathedge.shared.domain.GroupRole
import zio.json.*

/** [[gathedge.shared.api.GroupEndpoints.create]]'s body: the group's name. The caller becomes its sole admin. */
final case class CreateGroupRequest(name: String) derives JsonCodec

/** One row of [[GroupDetail.members]] — an account on the group's roster. Only present for a viewer who is themself a
  * member; see [[GroupDetail]].
  */
final case class GroupMemberSummary(
  userId: Long,
  email: Option[String],
  isGuest: Boolean,
  role: GroupRole,
  joinedAt: Long,
) derives JsonCodec

/** One row of [[GroupDetail.tags]] — a tag currently attached to the group, with enough of its owner's identity to show
  * who contributed it. Visible to everyone, same as the group's name — a group's tag list is not part of its private
  * roster.
  */
final case class GroupTagSummary(
  id: Long,
  name: String,
  wordCount: Long,
  ownerEmail: Option[String],
  ownerIsGuest: Boolean,
) derives JsonCodec

/** `GET /api/groups/{groupId}`'s answer.
  *
  * @param members
  *   the roster, populated only when the caller is themself a member — an outsider or a signed-out visitor still sees
  *   `memberCount` on the plain [[gathedge.shared.domain.Group]] listing, but not who they are. Empty otherwise.
  * @param inviteCode
  *   populated only when the caller is an admin of this group — visibility no wider than who may regenerate it.
  */
final case class GroupDetail(
  id: Long,
  name: String,
  memberCount: Long,
  viewerRole: Option[GroupRole],
  inviteCode: Option[String],
  members: List[GroupMemberSummary],
  tags: List[GroupTagSummary],
) derives JsonCodec

/** `PUT /api/groups/{groupId}`'s body: the group's new display name. Follows [[CreateGroupRequest]]'s own validation —
  * no per-account uniqueness, unlike a tag's own rename — see `Validation.validateGroupName`.
  */
final case class RenameGroupRequest(name: String) derives JsonCodec

/** `POST /api/groups/join`'s body. Not nested under a group id — the caller doesn't know it until the code resolves
  * one. Redeeming always joins as [[GroupRole.Member]], and redeeming a code for a group the caller already belongs to
  * is a no-op, not a conflict.
  */
final case class JoinGroupRequest(code: String) derives JsonCodec

/** `POST /api/groups/{groupId}/invite-code/regenerate`'s answer: the group's freshly minted invite code. The previous
  * code stops working the moment this one is issued.
  */
final case class InviteCodeResponse(code: String) derives JsonCodec

/** `PUT /api/groups/{groupId}/members/{userId}/role`'s body. */
final case class SetMemberRoleRequest(role: GroupRole) derives JsonCodec
