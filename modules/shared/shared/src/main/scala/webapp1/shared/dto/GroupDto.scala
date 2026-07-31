package webapp1.shared.dto

import zio.json.*
import webapp1.shared.domain.GroupRole

final case class CreateGroupRequest(name: String) derives JsonCodec
final case class CreatePairRequest(source: String, target: String) derives JsonCodec
final case class InviteMemberRequest(email: String, role: GroupRole) derives JsonCodec
final case class UpdateRoleRequest(role: GroupRole) derives JsonCodec
