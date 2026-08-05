package webapp1.backend.db

/** `emailVerifiedAt` is `None` until the address is proven — either by following a verification link or by arriving
  * from a provider that asserts `email_verified`. Whether that actually blocks a password login is
  * `app.require-email-verification`; the column is filled in either way.
  */
final case class UserRow(
  id: Long,
  email: String,
  passwordHash: Option[String],
  isAdmin: Boolean,
  theme: String,
  createdAt: Long,
  emailVerifiedAt: Option[Long],
)

/** One external identity linked to a user. `provider` holds [[webapp1.backend.service.OAuthProvider]]'s wire name and
  * `subject` the provider's own stable identifier for the user; the pair is unique. `email` is whatever the provider
  * reported at link time, kept for display only — it is never used to match an account, see
  * `AuthService.loginWithOAuth`.
  */
final case class OAuthIdentityRow(
  id: Long,
  userId: Long,
  provider: String,
  subject: String,
  email: Option[String],
  createdAt: Long,
)

final case class SessionRow(id: String, userId: Long, createdAt: Long, expiresAt: Long, revokedAt: Option[Long])

final case class TodoItemRow(id: Long, userId: Long, text: String, status: String, createdAt: Long)

final case class GroupRow(id: Long, name: String, createdAt: Long)

final case class GroupMemberRow(groupId: Long, userId: Long, role: String, joinedAt: Long)

final case class GroupPairRow(
  id: Long,
  groupId: Long,
  source: String,
  target: String,
  createdBy: Long,
  createdByEmail: String,
  createdAt: Long,
)

/** A single-use proof-of-address link. `consumedAt` set means it has already been redeemed; rows are pruned by
  * [[webapp1.backend.service.SessionReaper]] once they are past `expiresAt`.
  */
final case class EmailVerificationTokenRow(
  id: Long,
  userId: Long,
  token: String,
  createdAt: Long,
  expiresAt: Long,
  consumedAt: Option[Long],
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
