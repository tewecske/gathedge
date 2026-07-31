package webapp1.backend.db

final case class UserRow(
  id: Long,
  email: String,
  passwordHash: Option[String],
  isAdmin: Boolean,
  theme: String,
  googleSubject: Option[String],
  createdAt: Long,
)

final case class SessionRow(
  id: String,
  userId: Long,
  createdAt: Long,
  expiresAt: Long,
  revokedAt: Option[Long],
)

final case class TodoItemRow(
  id: Long,
  userId: Long,
  text: String,
  status: String,
  createdAt: Long,
)

final case class GroupRow(
  id: Long,
  name: String,
  createdAt: Long,
)

final case class GroupMemberRow(
  groupId: Long,
  userId: Long,
  role: String,
  joinedAt: Long,
)

final case class GroupPairRow(
  id: Long,
  groupId: Long,
  source: String,
  target: String,
  createdBy: Long,
  createdByEmail: String,
  createdAt: Long,
)

final case class GroupInvitationRow(
  id: Long,
  groupId: Long,
  email: String,
  role: String,
  token: String,
  invitedBy: Long,
  createdAt: Long,
  expiresAt: Long,
  acceptedAt: Option[Long],
)
