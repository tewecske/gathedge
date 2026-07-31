package webapp1.shared.domain

import zio.json.*

final case class GroupMember(userId: Long, email: String, role: GroupRole, joinedAt: String) derives JsonCodec

final case class InvitationInfo(
  groupName: String,
  email: String,
  role: GroupRole,
  expired: Boolean,
  accepted: Boolean,
) derives JsonCodec
