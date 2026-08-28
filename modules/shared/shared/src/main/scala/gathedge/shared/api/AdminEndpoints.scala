package gathedge.shared.api

import gathedge.shared.domain.User
import gathedge.shared.dto.{
  AdminUserDetail,
  AuditPage,
  ClearRateLimitRequest,
  CreateUserRequest,
  DeleteWordFormRequest,
  LoginAttemptEntry,
  MyPlayPage,
  PruneResult,
  RateLimitEntry,
  RouteUsage,
  SuspiciousUser,
  SystemOverview,
  UpdateUserRequest,
  UserPage,
  WordFormAnomaly,
}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure, withCodecError}
import ApiSchemas.given

/** Administrator user management, account diagnostics, the audit trail and the system overview.
  *
  * The admin check is an aspect on the `Routes` value, not part of any description here, so these look like every other
  * resource; what makes them admin-only lives in `AdminRoutes`. `adminOnly` answers 401 with no session and 403 with a
  * non-administrator one; only the 401 is described, for the reason recorded on [[ApiEndpoint.failure]] — a page never
  * routes a non-administrator here in the first place, so its 403 is a client that went somewhere it was not sent.
  *
  * The diagnostic endpoints answer *about* accounts, never out of them: no response here carries a password hash, a
  * session id, an OAuth subject, a verification token, or any configured secret. See the note on
  * `gathedge.shared.dto.AdminOpsDto` for what is projected away and why.
  */
object AdminEndpoints {

  private val userId   = PathCodec.long("id")
  private val provider = PathCodec.string("provider")

  /** See [[deleteUser]] for why an empty 204 is described as a status codec and never as `.out[Unit]`. */
  private val noContent = HttpCodec.status(Status.NoContent)

  /** The list endpoints page and narrow through query parameters, all optional; a caller that sends none gets the first
    * page of everything, in the listing's own order. Optional rather than defaulted because a defaulted query codec
    * would put the default into the OpenAPI document as if the client had to send it — `dto.Paging` is where the
    * defaults actually live, and both ends read them from there.
    *
    * `page` is one-based — `page=2` is the second page, the same number the address bar shows; `dto.Paging.firstPage`
    * is that number and `dto.Paging.offset` is the only place it becomes rows to skip. `sort` names a column out of
    * `dto.UserSort`/`dto.AuditSort` and `dir` is a `dto.SortDirection`; neither is validated, because an unrecognised
    * value means "the listing's own order" rather than a malformed request — a client is free to stop sending a sort it
    * no longer offers.
    */
  private val pageQuery            = HttpCodec.query[Int]("page").optional
  private val pageSizeQuery        = HttpCodec.query[Int]("pageSize").optional
  private val sortQuery            = HttpCodec.query[String]("sort").optional
  private val dirQuery             = HttpCodec.query[String]("dir").optional
  private val searchQuery          = HttpCodec.query[String]("q").optional
  private val limitQuery           = HttpCodec.query[Int]("limit").optional
  private val actionQuery          = HttpCodec.query[String]("action").optional
  private val actorQuery           = HttpCodec.query[Long]("actorId").optional
  private val targetQuery          = HttpCodec.query[String]("targetId").optional
  private val outcomeQuery         = HttpCodec.query[String]("outcome").optional
  private val windowQuery          = HttpCodec.query[Int]("windowHours").optional
  private val actionThresholdQuery = HttpCodec.query[Int]("actionThreshold").optional
  private val ipThresholdQuery     = HttpCodec.query[Int]("ipThreshold").optional
  private val gameIdQuery          = HttpCodec.query[Long]("gameId").optional

  /** One page of accounts, narrowed by `q` — a case-insensitive substring of the address.
    *
    * `AdminService.listUsers` is still a `UIO`, so the 400 is `withCodecError`'s: a `page` or `pageSize` that is not a
    * number never reaches the handler.
    */
  val listUsers = {
    Endpoint(Method.GET / "api" / "admin" / "users")
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .query(searchQuery)
      .withCodecError
      .out[UserPage]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  val getUser = {
    Endpoint(Method.GET / "api" / "admin" / "users" / userId)
      .out[User]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  val createUser = {
    Endpoint(Method.POST / "api" / "admin" / "users")
      .in[CreateUserRequest]
      .withCodecError
      .out[User](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  val updateUser = {
    Endpoint(Method.PUT / "api" / "admin" / "users" / userId)
      .in[UpdateUserRequest]
      .withCodecError
      .out[User]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** The success response is described as a bare status, not as `.out[Unit](Status.NoContent)`.
    *
    * Both put a 204 with an empty body on the wire, but `out[Unit]` also installs a *body* codec, and decoding it needs
    * to know the body is empty. A 204 must not carry `Content-Length` (RFC 9110 §8.6), and zio-http's Scala.js body
    * (`FetchBodyInternal.isEmpty`) reports empty only when that header says `0` — so a browser client built from this
    * description fails every delete with "Non-empty body cannot be decoded as Unit". A status-only codec has no body to
    * decode and sidesteps it. Every other 204 in this package is described the same way for the same reason.
    */
  val deleteUser = {
    Endpoint(Method.DELETE / "api" / "admin" / "users" / userId)
      .outCodec(HttpCodec.status(Status.NoContent))
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Everything the administrator's account screen needs, in one request rather than five.
    *
    * The five things it gathers — the account, its linked providers, its sessions, its outstanding confirmation link
    * and its lockout state — are always read together and never useful apart, and the lockout half is read out of a
    * process-local `Ref` rather than the database, so splitting it into its own endpoint would buy nothing.
    */
  val userDetail = {
    Endpoint(Method.GET / "api" / "admin" / "users" / userId / "detail")
      .out[AdminUserDetail]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Confirms an address on the account's behalf, for the case where the user cannot receive the link at all. */
  val verifyUserEmail = {
    Endpoint(Method.POST / "api" / "admin" / "users" / userId / "verify-email")
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Sends a fresh confirmation link. Unlike the self-service `AuthEndpoints.resendVerification` this neither hides
    * whether the account exists nor spends a rate-limit budget — the caller is already an authenticated administrator,
    * so there is nothing to conceal from them and no one to throttle.
    */
  val resendUserVerification = {
    Endpoint(Method.POST / "api" / "admin" / "users" / userId / "verification" / "resend")
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Ends every session the account holds. There is no per-session form of this: `AdminSessionInfo` carries no
    * identifier, because the sessions table's primary key *is* the bearer token.
    */
  val revokeUserSessions = {
    Endpoint(Method.DELETE / "api" / "admin" / "users" / userId / "sessions")
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Detaches one social account. Answers 409 when it is the account's last way to sign in — the same rule the user's
    * own settings screen is held to, enforced here as well rather than only there.
    */
  val unlinkUserIdentity = {
    Endpoint(Method.DELETE / "api" / "admin" / "users" / userId / "identities" / provider)
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Lets a locked-out account sign in again, clearing both dimensions of the lock: the address, and every origin it
    * was recently attempted from.
    */
  val clearUserLockout = {
    Endpoint(Method.DELETE / "api" / "admin" / "users" / userId / "lockout")
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** One page of the audit trail, most recent first unless `sort` says otherwise.
    *
    * Offset paging, not the cursor this used to take. A cursor (`before=<oldest occurredAt shown>`) is the stabler of
    * the two under concurrent writes — offsets shift when a row is inserted above the page being read — but it can only
    * ever walk backwards from where the reader already is, so it cannot number pages, cannot jump, and has nothing to
    * count a total against. Numbered pages and a row count were the point; this is what they cost.
    */
  val auditLog = {
    Endpoint(Method.GET / "api" / "admin" / "audit")
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .query(actionQuery)
      .query(actorQuery)
      .query(targetQuery)
      .withCodecError
      .out[AuditPage]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Recorded sign-in attempts across every address, most recent first. */
  val loginAttempts = {
    Endpoint(Method.GET / "api" / "admin" / "login-attempts")
      .query(limitQuery)
      .query(outcomeQuery)
      .withCodecError
      .out[List[LoginAttemptEntry]]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Every rate-limiter key currently holding failures. `AdminService.rateLimits` is a `UIO` — it reads a `Ref` — so
    * only the aspect and a defect can fail this.
    */
  val rateLimits = {
    Endpoint(Method.GET / "api" / "admin" / "rate-limits").out[List[RateLimitEntry]].outFailure(failure.unauthorized)
  }

  val clearRateLimits = {
    Endpoint(Method.POST / "api" / "admin" / "rate-limits" / "clear")
      .in[ClearRateLimitRequest]
      .withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Configuration, runtime and row counts. `SystemService.overview` cannot fail. */
  val systemOverview = {
    Endpoint(Method.GET / "api" / "admin" / "system").out[SystemOverview].outFailure(failure.unauthorized)
  }

  /** Runs the sweep `SessionReaper` performs hourly, now. `SystemService.prune` cannot fail. */
  val systemPrune = {
    Endpoint(Method.POST / "api" / "admin" / "system" / "prune").out[PruneResult].outFailure(failure.unauthorized)
  }

  /** Every `word_forms` `(form word, relation)` pair claiming more than the anomaly threshold's worth of distinct
    * lemmas — see `gathedge.shared.dto.WordFormAnomaly`. `AdminService.wordFormAnomalies` is a `UIO`.
    */
  val wordFormAnomalies = {
    Endpoint(Method.GET / "api" / "admin" / "word-forms" / "anomalies")
      .out[List[WordFormAnomaly]]
      .outFailure(failure.unauthorized)
  }

  /** Deletes every `word_forms` row for one `(form word, relation)` pair. */
  val deleteWordFormAnomaly = {
    Endpoint(Method.POST / "api" / "admin" / "word-forms" / "anomalies" / "delete")
      .in[DeleteWordFormRequest]
      .withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Every (method, route) pair `usage_events` holds a row for, most-used first is the caller's job — the same list
    * sorted the other way is the least-used report, so there is only one endpoint for both.
    */
  val usageRoutes = {
    Endpoint(Method.GET / "api" / "admin" / "usage" / "routes")
      .query(windowQuery)
      .withCodecError
      .out[List[RouteUsage]]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Accounts that crossed either threshold in the window — too many requests, or too many distinct origins. See
    * `gathedge.backend.service.UsageStatsService` for what "crossed" means.
    */
  val usageSuspicious = {
    Endpoint(Method.GET / "api" / "admin" / "usage" / "suspicious")
      .query(windowQuery)
      .query(actionThresholdQuery)
      .query(ipThresholdQuery)
      .withCodecError
      .out[List[SuspiciousUser]]
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** One page of `id`'s game plays across every game, most recently started first unless `sort` says otherwise. See
    * `AdminService.userPlays`.
    */
  val userPlays = {
    Endpoint(Method.GET / "api" / "admin" / "users" / userId / "plays")
      .query(gameIdQuery)
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .withCodecError
      .out[MyPlayPage]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = {
    List(
      listUsers,
      getUser,
      createUser,
      updateUser,
      deleteUser,
      userDetail,
      userPlays,
      verifyUserEmail,
      resendUserVerification,
      revokeUserSessions,
      unlinkUserIdentity,
      clearUserLockout,
      auditLog,
      loginAttempts,
      rateLimits,
      clearRateLimits,
      systemOverview,
      systemPrune,
      wordFormAnomalies,
      deleteWordFormAnomaly,
      usageRoutes,
      usageSuspicious,
    )
  }
}
