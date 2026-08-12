package gathedge.backend.db

/** `emailVerifiedAt` is `None` until the address is proven — either by following a verification link or by arriving
  * from a provider that asserts `email_verified`. Whether that actually blocks a password login is
  * `app.require-email-verification`; the column is filled in either way.
  *
  * `email` is `None` exactly when `isGuest` is true — an account minted without credentials so that the vocabulary
  * needs no sign-up. A NULL address is what lets any number of guests coexist under the column's `UNIQUE` index, and it
  * is why `findByEmail` cannot accidentally return one: nothing equals NULL.
  */
final case class UserRow(
  id: Long,
  email: Option[String],
  passwordHash: Option[String],
  isAdmin: Boolean,
  theme: String,
  locale: String,
  createdAt: Long,
  emailVerifiedAt: Option[Long],
  isGuest: Boolean,
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

/** One lexical unit, shared by every account.
  *
  * `textNorm` is the lowercased form and the only column search touches. `gender` and `frequencyRank` are never null —
  * `""` and a large sentinel stand in — because a NULL is distinct in a `UNIQUE` index on both dialects and sorts in a
  * dialect-dependent place; see the migration's comment. `createdBy` is `None` for an imported row, and becomes `None`
  * again if the account that typed one is deleted: a word other people have tagged outlives its author.
  */
final case class WordRow(
  id: Long,
  language: String,
  text: String,
  textNorm: String,
  partOfSpeech: String,
  gender: String,
  frequencyRank: Int,
  source: String,
  createdBy: Option[Long],
  createdAt: Long,
)

/** One direction of a translation. Both directions are stored, so every read is a filter on `sourceWordId` and the
  * practice screen can prompt either way round.
  *
  * `origin` is `dictionary` for what the dictionary asserts, `pivot` for a German–Hungarian pair derived through a
  * shared English sense, and `user` for one somebody typed — which is the only case where `createdBy` is set.
  */
final case class WordTranslationRow(
  id: Long,
  sourceWordId: Long,
  targetWordId: Long,
  origin: String,
  createdBy: Option[Long],
  createdAt: Long,
)

/** A label one account puts on words. `nameNorm` is the lowercased form the per-account uniqueness is on. */
final case class TagRow(id: Long, userId: Long, name: String, nameNorm: String, createdAt: Long)

/** One word carrying one tag — and, since a tag belongs to exactly one account, the whole of what "this word is in my
  * vocabulary" means.
  */
final case class WordTagRow(id: Long, wordId: Long, tagId: Long, createdAt: Long)

/** A guest account's transfer code. The `code` column *is* the bearer credential, like `SessionRow.id`: it must never
  * reach a log line, and it is answered to its owner exactly once, when it is minted.
  */
final case class GuestClaimCodeRow(
  id: Long,
  userId: Long,
  code: String,
  createdAt: Long,
  lastUsedAt: Option[Long],
  revokedAt: Option[Long],
)
