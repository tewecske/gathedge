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

/** A single-use "forgot password" link, the same shape as [[EmailVerificationTokenRow]]. `consumedAt` set means it has
  * already been redeemed; rows are pruned by [[gathedge.backend.service.SessionReaper]] once past `expiresAt`.
  */
final case class PasswordResetTokenRow(
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

/** One API request, written by `RouteSupport.usageTracking` on every call, session or none. `route` is normalized
  * rather than the raw path — see the note on the migration that creates this table — so aggregation groups requests by
  * endpoint shape instead of by exact URL. `userId` is `None` for an anonymous or invalid-session caller, and becomes
  * `None` again if the account is later deleted, like [[LoginAttemptRow.userId]].
  */
final case class UsageEventRow(
  id: Long,
  createdAt: Long,
  method: String,
  route: String,
  status: Int,
  userId: Option[Long],
  ip: Option[String],
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

/** One translation of one word, marked as a practice answer inside one tag.
  *
  * A tag says the word is being learned; this says which of its translations the practice screen should check against,
  * which is a different question — most words have several translations and only some are the sense being learned.
  *
  * Both directions are stored, like [[WordTranslationRow]]: the row `(Haus, lesson1, ház)` is always accompanied by
  * `(ház, lesson1, Haus)`, so a prompt works either way round with no union. Both words also carry the tag, since a
  * pair whose answer is not itself collected is a question with a missing half — which is why this is written through
  * `WordRepository.pairTranslation` rather than a bare insert.
  */
final case class WordTagPairRow(id: Long, wordId: Long, tagId: Long, translationWordId: Long, createdAt: Long)

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

/** One quiz, scoped to a language pair and built from the tags in [[GameTagRow]].
  *
  * `slug` is generated once at creation and never changes — the permanent key a share link is built from, sized and
  * typed like [[PasswordResetTokenRow.token]] / [[GuestClaimCodeRow.code]]: an app-generated random identifier that
  * must never collide. `name` is the opposite: cosmetic, free to edit at will, which is why the two are separate
  * columns rather than one renameable field.
  */
final case class GameRow(
  id: Long,
  ownerUserId: Long,
  slug: String,
  name: String,
  sourceLanguage: String,
  targetLanguage: String,
  createdAt: Long,
  updatedAt: Long,
)

/** One tag a game draws its words from. A game can span several tags, so this is a join table exactly like
  * [[WordTagRow]], not a single column on [[GameRow]].
  */
final case class GameTagRow(id: Long, gameId: Long, tagId: Long)

/** One attempt at a game, by one account.
  *
  * `score`, `maxScore` and `wordCount` are denormalized here rather than derived from [[GamePlayAnswerRow]] on every
  * read — a play is read far more often than written to, and all three are cheap to maintain incrementally as answers
  * come in. `wordCount` and `maxScore` are fixed at the moment the play starts; `score` is the one column that changes
  * as it progresses. `finishedAt` is `None` for a play still in progress and set once, when it completes — there is no
  * separate "abandoned" state.
  */
final case class GamePlayRow(
  id: Long,
  gameId: Long,
  playerUserId: Long,
  score: Int,
  maxScore: Int,
  wordCount: Int,
  startedAt: Long,
  finishedAt: Option[Long],
)

/** One word pair asked and answered inside one play — the per-word-pair progression record the whole feature is built
  * on. There is no separate stats table: a play's own score fields are the per-play summary, and a later feature
  * aggregates this row across plays for anything more.
  *
  * Both `wordId` and `translationWordId` are stored, for the same reason [[WordTagPairRow]] stores both: a word usually
  * has several translations, and this row has to say which specific sense was tested, not merely which word was on
  * screen. Unlike [[WordTagPairRow]], these two columns deliberately do NOT cascade from `words` — see the migration's
  * comment: this table is a permanent scored history record, and a play's score must never silently change because a
  * dictionary row was later removed.
  */
final case class GamePlayAnswerRow(
  id: Long,
  playId: Long,
  wordId: Long,
  translationWordId: Long,
  position: Int,
  userAnswer: String,
  outcome: String,
  points: Int,
  answeredAt: Long,
)
