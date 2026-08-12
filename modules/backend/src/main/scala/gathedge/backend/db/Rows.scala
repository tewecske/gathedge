package gathedge.backend.db

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
  locale: String,
  createdAt: Long,
  emailVerifiedAt: Option[Long],
)

/** One external identity linked to a user. `provider` holds [[gathedge.backend.service.OAuthProvider]]'s wire name and
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

/** A single-use proof-of-address link. `consumedAt` set means it has already been redeemed; rows are pruned by
  * [[gathedge.backend.service.SessionReaper]] once they are past `expiresAt`.
  */
final case class EmailVerificationTokenRow(
  id: Long,
  userId: Long,
  token: String,
  createdAt: Long,
  expiresAt: Long,
  consumedAt: Option[Long],
)

/** One sign-in attempt, successful or not. The history behind the in-memory rate limiter, which knows only the current
  * 15-minute window and forgets it on restart.
  *
  * `email` is what the caller typed, normalized the same way [[gathedge.backend.service.RateLimitKey.email]] normalizes
  * it, so an attempt against an address that has no account is still attributable. `userId` is `None` in exactly that
  * case, and becomes `None` again if the account is later deleted — the row outlives it deliberately.
  */
final case class LoginAttemptRow(
  id: Long,
  email: String,
  userId: Option[Long],
  ip: Option[String],
  outcome: String,
  createdAt: Long,
)

/** The queryable half of the `security` logger: one row per administrator action, written by the same
  * `AdminService.audit` call that emits the log line.
  *
  * `actorEmail` is a snapshot rather than a join, because the row has to still name who acted after that account is
  * deleted. `detail` is prose for an administrator to read and must never carry a credential — see
  * [[QuillRepository.logged]] for the same rule applied to log lines.
  */
final case class AuditLogRow(
  id: Long,
  occurredAt: Long,
  actorUserId: Option[Long],
  actorEmail: Option[String],
  action: String,
  targetType: Option[String],
  targetId: Option[String],
  detail: Option[String],
  ip: Option[String],
)
