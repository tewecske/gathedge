package gathedge.shared.dto

import gathedge.shared.domain.{OAuthProvider, User}
import zio.json.*

/** Everything an administrator may see *about* an account, as opposed to the account's own data.
  *
  * The boundary this file sits on is the one summary.md draws: an administrator manages accounts, not the content
  * accounts own. Nothing here reaches into whatever a feature stores on a user's behalf, and three things the server
  * holds are deliberately projected away rather than carried:
  *
  *   - the password hash;
  *   - the session id, which *is* the bearer credential — [[AdminSessionInfo]] has no identifier at all, not even a
  *     prefix, which is why revocation is per account rather than per session;
  *   - the verification token and the provider's `subject`, for the same reason.
  *
  * Timestamps are epoch millis, like every timestamp the backend stores. (`User.createdAt` is the odd one out: it is
  * the same number, already rendered as a string, and predates these.)
  */

/** One session row, stripped of its id. `active` is "could still authenticate right now", which is what an
  * administrator looking at a support ticket actually wants; `revokedAt` and `expiresAt` say why not.
  */
final case class AdminSessionInfo(
  createdAt: Long,
  expiresAt: Long,
  revokedAt: Option[Long],
  active: Boolean,
) derives JsonCodec

/** One linked social account. `email` is what the provider reported at link time and is display metadata only — it is
  * never what a sign-in matches on. The provider's `subject`, which *is*, never leaves the server.
  */
final case class AdminIdentityInfo(
  provider: OAuthProvider,
  email: Option[String],
  createdAt: Long,
) derives JsonCodec

/** An outstanding or spent confirmation link, without the token. Enough to answer "did they get a link, and is it still
  * good", which is the whole of what an administrator needs before offering to send another.
  */
final case class AdminVerificationTokenInfo(
  createdAt: Long,
  expiresAt: Long,
  consumed: Boolean,
  expired: Boolean,
) derives JsonCodec

/** What the rate limiter currently holds against an account.
  *
  * `maxAttempts` and `windowMinutes` come from the server rather than being hard-coded in the page, so the screen
  * cannot drift from the policy it describes. `blockedKeys` names which dimension tripped — the address, an origin, or
  * both — because clearing one and not the other leaves the user still locked out.
  */
final case class LockoutStatus(
  blocked: Boolean,
  attempts: Int,
  maxAttempts: Int,
  windowMinutes: Int,
  retryAfterMillis: Long,
  blockedKeys: List[String],
) derives JsonCodec

/** One recorded sign-in attempt. `outcome` is one of the values in [[LoginOutcome]].
  *
  * `email` is what was typed, so an attempt against an address with no account is still shown; `userId` is empty in
  * exactly that case, and becomes empty again once the account is deleted.
  */
final case class LoginAttemptEntry(
  id: Long,
  occurredAt: Long,
  email: String,
  userId: Option[Long],
  ip: Option[String],
  outcome: String,
) derives JsonCodec

/** The `outcome` vocabulary of [[LoginAttemptEntry]], as plain strings.
  *
  * Not an enum: the values are written by the backend and only ever read for display and filtering, and a string keeps
  * a row written by an older build readable by a newer one instead of failing to decode.
  */
object LoginOutcome {
  val success: String          = "success"
  val unknownEmail: String     = "unknown_email"
  val badPassword: String      = "bad_password"
  val noPassword: String       = "no_password"
  val emailNotVerified: String = "email_not_verified"
  val rateLimited: String      = "rate_limited"

  val all: List[String] = List(success, unknownEmail, badPassword, noPassword, emailNotVerified, rateLimited)

  /** What the screen shows instead of the stored value. Falls back to the raw value so a row written by a newer build
    * still renders.
    */
  def display(outcome: String): String = {
    outcome match {
      case `success`          =>
        "Signed in"
      case `unknownEmail`     =>
        "No such account"
      case `badPassword`      =>
        "Wrong password"
      case `noPassword`       =>
        "No password set"
      case `emailNotVerified` =>
        "Email not confirmed"
      case `rateLimited`      =>
        "Blocked by rate limit"
      case other              =>
        other
    }
  }
}

/** One page of the administrator's view of an account, gathered in a single request so the screen does not fan out into
  * five.
  */
final case class AdminUserDetail(
  user: User,
  hasPassword: Boolean,
  emailVerifiedAt: Option[Long],
  identities: List[AdminIdentityInfo],
  sessions: List[AdminSessionInfo],
  activeSessions: Int,
  verificationTokens: List[AdminVerificationTokenInfo],
  lockout: LockoutStatus,
  recentLoginAttempts: List[LoginAttemptEntry],
) derives JsonCodec

/** One administrator action, as recorded in `audit_log`.
  *
  * `actorEmail` is a snapshot taken when the action happened rather than a join, so the entry still names who acted
  * after that account is deleted — which is exactly the case an audit trail exists for.
  */
final case class AuditEntry(
  id: Long,
  occurredAt: Long,
  actorUserId: Option[Long],
  actorEmail: Option[String],
  action: String,
  targetType: Option[String],
  targetId: Option[String],
  detail: Option[String],
  ip: Option[String],
) derives JsonCodec

/** The `action` vocabulary of [[AuditEntry]]. Strings, for the reason given on [[LoginOutcome]]. */
object AuditAction {
  val userCreate: String                   = "user.create"
  val userUpdate: String                   = "user.update"
  val userDelete: String                   = "user.delete"
  val userPasswordReset: String            = "user.password_reset"
  val userVerifyEmail: String              = "user.verify_email"
  val userVerificationResend: String       = "user.verification_resend"
  val userSessionsRevoked: String          = "user.sessions_revoked"
  val userOAuthUnlink: String              = "user.oauth_unlink"
  val userLockoutCleared: String           = "user.lockout_cleared"
  val systemPrune: String                  = "system.prune"
  val systemRateLimitsCleared: String      = "system.rate_limits_cleared"
  val systemWordFormAnomalyDeleted: String = "system.word_form_anomaly_deleted"

  val all: List[String] = {
    List(
      userCreate,
      userUpdate,
      userDelete,
      userPasswordReset,
      userVerifyEmail,
      userVerificationResend,
      userSessionsRevoked,
      userOAuthUnlink,
      userLockoutCleared,
      systemPrune,
      systemRateLimitsCleared,
      systemWordFormAnomalyDeleted,
    )
  }
}

/** One live rate-limiter key. The whole of the limiter's state is per-process and empties on restart —
  * [[LoginAttemptEntry]] is the durable record, this is only "who is blocked at this moment".
  */
final case class RateLimitEntry(
  key: String,
  attempts: Int,
  blocked: Boolean,
  oldestAttemptAt: Option[Long],
  retryAfterMillis: Long,
) derives JsonCodec

/** Clears one rate-limiter key, or every key when `key` is empty. */
final case class ClearRateLimitRequest(key: Option[String]) derives JsonCodec

/** One `word_forms` fan-out anomaly: `formText` claimed as a form of `lemmaCount` distinct lemmas under `relation`,
  * which is far more than a real inflected form ever is. The shape of the bug where a mislabeled wiktextract tag (a
  * German conjugation table's `auxiliary` note) turned `haben`/`sein` into a "form" of hundreds of verbs.
  */
final case class WordFormAnomaly(
  formWordId: Long,
  formText: String,
  language: String,
  relation: String,
  lemmaCount: Long,
) derives JsonCodec

/** Deletes every `word_forms` row for one `(form word, relation)` pair -- the cleanup action for a [[WordFormAnomaly]].
  */
final case class DeleteWordFormRequest(formWordId: Long, relation: String) derives JsonCodec

/** What this deployment is configured to do — never a value that would be a secret.
  *
  * Every credential-bearing setting is reduced to a boolean or dropped: there is no password, no client secret and no
  * bootstrap credential here, and `databaseUrl` has any userinfo stripped. `productionIssues` is `AppConfig`'s own
  * startup self-check, which otherwise only ever reached the log.
  */
final case class ConfigSummary(
  env: String,
  production: Boolean,
  publicBaseUrl: String,
  serverHost: String,
  serverPort: Int,
  requireEmailVerification: Boolean,
  sessionCookieSecure: Boolean,
  configuredOAuthProviders: List[OAuthProvider],
  mailConfigured: Boolean,
  mailFrom: String,
  mailSmtpHost: Option[String],
  mailSmtpPort: Int,
  mailStartTls: Boolean,
  databaseUrl: String,
  databaseUser: String,
  databaseSchema: String,
  sessionValidityHours: Long,
  verificationValidityHours: Long,
  resetTokenValidityHours: Long,
  rateLimitMaxAttempts: Int,
  rateLimitWindowMinutes: Long,
  nettyMaxThreads: Int,
  trustedProxyHops: Int,
  loginAttemptRetentionDays: Int,
  guestRetentionDays: Int,
  captchaConfigured: Boolean,
  captchaLoginThreshold: Int,
  quotaTagsSoft: Int,
  quotaTagsHard: Int,
  quotaWordPairsSoft: Int,
  quotaWordPairsHard: Int,
  productionIssues: List[String],
) derives JsonCodec

/** One applied Flyway migration. Without `installed_on`: Flyway declares it as a native `TIMESTAMP`, the one type this
  * schema avoids because the two dialects disagree about it.
  */
final case class MigrationInfo(
  rank: Int,
  version: Option[String],
  description: String,
  success: Boolean,
) derives JsonCodec

/** A background daemon's own account of itself. `lastRunAt` empty means it has not completed a pass yet — on a fresh
  * boot that is normal, an hour later it is not.
  */
final case class JobStatus(
  name: String,
  intervalMinutes: Long,
  lastRunAt: Option[Long],
  lastOutcome: Option[String],
  lastError: Option[String],
) derives JsonCodec

final case class RuntimeInfo(
  apiVersion: String,
  startedAt: Long,
  uptimeMillis: Long,
  jvmVersion: String,
  availableProcessors: Int,
  heapUsedBytes: Long,
  heapMaxBytes: Long,
  liveThreads: Int,
  migrations: List[MigrationInfo],
) derives JsonCodec

/** Row counts across the whole schema. Counts only, and a feature adding a row here should keep it that way: a table
  * may hold data no administrator is allowed to read, and a count says how much the deployment holds without saying
  * what anyone wrote.
  */
final case class DbStats(
  users: Long,
  admins: Long,
  unverifiedUsers: Long,
  usersWithoutPassword: Long,
  oauthIdentities: Long,
  sessions: Long,
  activeSessions: Long,
  verificationTokens: Long,
  expiredVerificationTokens: Long,
  loginAttempts: Long,
  failedLoginsLast24h: Long,
  auditEntries: Long,
  blockedRateLimitKeys: Long,
  /** Accounts with no address, minted so a visitor could tag a word without signing up. Counted apart from `users`
    * because the two grow for entirely different reasons.
    */
  guests: Long,
  words: Long,
  wordTranslations: Long,
  tags: Long,
) derives JsonCodec

final case class SystemOverview(
  config: ConfigSummary,
  runtime: RuntimeInfo,
  stats: DbStats,
  jobs: List[JobStatus],
) derives JsonCodec

/** What one maintenance sweep removed. The same sweep `SessionReaper` runs on its own hourly schedule. */
final case class PruneResult(
  sessions: Long,
  verificationTokens: Long,
  loginAttempts: Long,
  /** Guest accounts that were minted and then never used — no tags, no words, no transfer code. One holding anything at
    * all is never swept, so this counts abandoned rows rather than vocabularies.
    */
  guests: Long,
  rateLimitKeys: Long,
) derives JsonCodec

/** How many requests one route (method plus normalized path) received in the reported window. The most- and least-used
  * lists shown on the usage screen are the same rows, sorted the two opposite ways — there is no second query for
  * "least used".
  */
final case class RouteUsage(method: String, route: String, count: Long) derives JsonCodec

/** One account [[UsageStatsService]] flagged as unusual in the reported window, by exceeding either threshold on its
  * own — the two are independent signals, not a combined score. `email` is `None` for a guest.
  */
final case class SuspiciousUser(
  userId: Long,
  email: Option[String],
  eventCount: Long,
  distinctIpCount: Int,
) derives JsonCodec
