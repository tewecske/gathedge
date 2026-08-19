package gathedge.backend.service

import gathedge.backend.db.{
  AuditLogRepository,
  AuditLogRow,
  EmailVerificationTokenRepository,
  EmailVerificationTokenRow,
  LoginAttemptRepository,
  LoginAttemptRow,
  OAuthIdentityRepository,
  OAuthIdentityRow,
  SessionRepository,
  SessionRow,
  UserRepository,
  UserRow,
}
import gathedge.backend.security.PasswordHasher
import gathedge.shared.domain.{Locale, OAuthProvider, Theme, User}
import gathedge.shared.domain.Locale.code
import gathedge.shared.i18n.{MessageKeys, MessageRef}
import gathedge.shared.dto.{
  AdminIdentityInfo,
  AdminSessionInfo,
  AdminUserDetail,
  AdminVerificationTokenInfo,
  AuditAction,
  AuditEntry,
  AuditPage,
  LockoutStatus,
  LoginAttemptEntry,
  MyPlayPage,
  Paging,
  RateLimitEntry,
  UserPage,
}
import gathedge.shared.validation.Validation
import zio.*

import java.util.concurrent.TimeUnit

enum AdminFailure {
  case ValidationError(fieldErrors: Map[String, MessageRef])
  case DuplicateEmail
  case NotFound

  /** An administrator can't remove their own admin privileges or delete their own account — enforced here independent
    * of the UI (summary.md).
    */
  case SelfDemote
  case SelfDelete

  /** Detaching this provider would leave the account with nothing to sign in with. The same rule the account's own
    * settings screen is held to (`AuthFailure.LastCredential`); an administrator is not exempt from it, because the
    * account it would lock out is not theirs to lock out.
    */
  case LastCredential
}

/** Who is performing an administrator action, and from where.
  *
  * Carried rather than passed as a bare id because every mutating method now writes an `audit_log` row, and an audit
  * trail that cannot say where an action came from answers half the question it exists to answer.
  */
final case class AdminActor(userId: Long, clientIp: Option[String] = None)

object AdminActor {

  /** The application acting on its own behalf rather than an administrator: startup seeding, and test fixtures that
    * only want an account to exist. Id `0` matches no row, so the audit entry records no actor.
    */
  val system: AdminActor = AdminActor(0L, None)
}

trait AdminService {

  /** One page of accounts, narrowed to addresses containing `search` and ordered by `sort` (a `dto.UserSort` value).
    *
    * `page` and `pageSize` are expected already bounded — `AdminRoutes` applies `dto.Paging` on the way in, the same
    * place every other limit is capped.
    */
  def listUsers(
    page: Int,
    pageSize: Int,
    search: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): UIO[UserPage]

  def getUser(id: Long): IO[AdminFailure, User]
  def createUser(actor: AdminActor, email: String, password: String, isAdmin: Boolean): IO[AdminFailure, User]
  def updateUser(
    actor: AdminActor,
    id: Long,
    email: String,
    isAdmin: Boolean,
    password: Option[String],
  ): IO[AdminFailure, User]
  def deleteUser(actor: AdminActor, id: Long): IO[AdminFailure, Unit]

  /** Everything the administrator's account screen shows, gathered in one call. */
  def userDetail(id: Long): IO[AdminFailure, AdminUserDetail]

  /** One page of `id`'s game plays across every game, narrowed to games whose owner turned on `trackResults` — the same
    * rule that gates a game's own owner. [[AdminFailure.NotFound]] for an unknown `id`.
    */
  def userPlays(
    id: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): IO[AdminFailure, MyPlayPage]

  /** Confirms the address without a link, for a user who cannot receive one. */
  def verifyEmailFor(actor: AdminActor, id: Long): IO[AdminFailure, Unit]

  /** Sends a fresh confirmation link, unconditionally — see `AuthService.issueVerificationFor` for why this is not
    * `AuthService.resendVerification`.
    */
  def resendVerificationFor(actor: AdminActor, id: Long): IO[AdminFailure, Unit]

  /** Ends every session the account holds. */
  def revokeSessions(actor: AdminActor, id: Long): IO[AdminFailure, Unit]

  /** Detaches one social account, unless it is the account's last credential. */
  def unlinkIdentity(actor: AdminActor, id: Long, provider: OAuthProvider): IO[AdminFailure, Unit]

  /** Lets a locked-out account sign in again, clearing both dimensions of the lock. */
  def clearLockout(actor: AdminActor, id: Long): IO[AdminFailure, Unit]

  /** One page of the audit trail, with the total the same three filters match. */
  def auditLog(
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): UIO[AuditPage]

  def loginAttempts(limit: Int, outcome: Option[String]): UIO[List[LoginAttemptEntry]]

  /** Every rate-limiter key currently holding failures. Reads a `Ref`, so it cannot fail. */
  def rateLimits: UIO[List[RateLimitEntry]]

  /** Clears one key, or every key when `key` is empty. */
  def clearRateLimits(actor: AdminActor, key: Option[String]): UIO[Unit]
}

object AdminService {
  def listUsers(
    page: Int,
    pageSize: Int,
    search: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): URIO[AdminService, UserPage] =
    ZIO.serviceWithZIO[AdminService](_.listUsers(page, pageSize, search, sort, descending))

  def getUser(id: Long): ZIO[AdminService, AdminFailure, User] =
    ZIO.serviceWithZIO[AdminService](_.getUser(id))

  def createUser(
    actor: AdminActor,
    email: String,
    password: String,
    isAdmin: Boolean,
  ): ZIO[AdminService, AdminFailure, User] =
    ZIO.serviceWithZIO[AdminService](_.createUser(actor, email, password, isAdmin))

  def updateUser(
    actor: AdminActor,
    id: Long,
    email: String,
    isAdmin: Boolean,
    password: Option[String],
  ): ZIO[AdminService, AdminFailure, User] =
    ZIO.serviceWithZIO[AdminService](_.updateUser(actor, id, email, isAdmin, password))

  def deleteUser(actor: AdminActor, id: Long): ZIO[AdminService, AdminFailure, Unit] =
    ZIO.serviceWithZIO[AdminService](_.deleteUser(actor, id))

  def userDetail(id: Long): ZIO[AdminService, AdminFailure, AdminUserDetail] =
    ZIO.serviceWithZIO[AdminService](_.userDetail(id))

  def userPlays(
    id: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): ZIO[AdminService, AdminFailure, MyPlayPage] =
    ZIO.serviceWithZIO[AdminService](_.userPlays(id, gameId, page, pageSize, sort, descending))

  def verifyEmailFor(actor: AdminActor, id: Long): ZIO[AdminService, AdminFailure, Unit] =
    ZIO.serviceWithZIO[AdminService](_.verifyEmailFor(actor, id))

  def resendVerificationFor(actor: AdminActor, id: Long): ZIO[AdminService, AdminFailure, Unit] =
    ZIO.serviceWithZIO[AdminService](_.resendVerificationFor(actor, id))

  def revokeSessions(actor: AdminActor, id: Long): ZIO[AdminService, AdminFailure, Unit] =
    ZIO.serviceWithZIO[AdminService](_.revokeSessions(actor, id))

  def unlinkIdentity(actor: AdminActor, id: Long, provider: OAuthProvider): ZIO[AdminService, AdminFailure, Unit] =
    ZIO.serviceWithZIO[AdminService](_.unlinkIdentity(actor, id, provider))

  def clearLockout(actor: AdminActor, id: Long): ZIO[AdminService, AdminFailure, Unit] =
    ZIO.serviceWithZIO[AdminService](_.clearLockout(actor, id))

  def auditLog(
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): URIO[AdminService, AuditPage] =
    ZIO.serviceWithZIO[AdminService](_.auditLog(page, pageSize, sort, descending, action, actorId, targetId))

  def loginAttempts(limit: Int, outcome: Option[String]): URIO[AdminService, List[LoginAttemptEntry]] =
    ZIO.serviceWithZIO[AdminService](_.loginAttempts(limit, outcome))

  def rateLimits: URIO[AdminService, List[RateLimitEntry]] =
    ZIO.serviceWithZIO[AdminService](_.rateLimits)

  def clearRateLimits(actor: AdminActor, key: Option[String]): URIO[AdminService, Unit] =
    ZIO.serviceWithZIO[AdminService](_.clearRateLimits(actor, key))

  /** The window `clearLockout` looks back over when collecting the origins to unblock. The limiter's own window is 15
    * minutes, so an hour comfortably covers every key that could still be holding a failure while leaving out addresses
    * from days ago.
    */
  val lockoutOriginWindow: Duration = 1.hour

  /** How much of an account's sign-in history the detail view carries. Enough to see a burst of failures and where it
    * came from; not so much that the response becomes a log export.
    */
  val recentAttemptLimit: Int = 25

  val live: URLayer[
    UserRepository & SessionRepository & OAuthIdentityRepository & EmailVerificationTokenRepository &
      LoginAttemptRepository & AuditLogRepository & AuditTrail & PasswordHasher & RateLimiter & AuthService &
      GameService,
    AdminService,
  ] = ZLayer.fromFunction(AdminServiceLive.apply)
}

/** Every administrator action that changes an account is recorded twice: once to the `security` slf4j logger, which is
  * what an operator watches, and once to `audit_log`, which is what the admin screens read. Both go through
  * [[AuditTrail]] so the two cannot describe different sets of events.
  *
  * Nothing here reads a user's own data. `userDetail` reports which sessions and providers an account has and how its
  * sign-ins have gone, and no more: the session id, the OAuth subject, the verification token and the password hash are
  * all projected away before anything leaves this file.
  */
final case class AdminServiceLive(
  userRepo: UserRepository,
  sessionRepo: SessionRepository,
  identityRepo: OAuthIdentityRepository,
  tokenRepo: EmailVerificationTokenRepository,
  attemptRepo: LoginAttemptRepository,
  auditRepo: AuditLogRepository,
  auditTrail: AuditTrail,
  hasher: PasswordHasher,
  rateLimiter: RateLimiter,
  authService: AuthService,
  gameService: GameService,
) extends AdminService {

  private def toDomain(row: UserRow): User = {
    User(
      row.id,
      row.email,
      row.isAdmin,
      Theme.fromString(row.theme).getOrElse(Theme.Light),
      Locale.fromString(row.locale).getOrElse(Locale.default),
      row.createdAt.toString,
      row.emailVerifiedAt.isDefined,
      row.isGuest,
    )
  }

  private def requireUser(id: Long): IO[AdminFailure, UserRow] = {
    userRepo.findById(id).orDie.flatMap(ZIO.fromOption(_).orElseFail(AdminFailure.NotFound))
  }

  /** Two queries rather than one: the page and the count of everything the same filter matches. There is no windowed
    * `COUNT(*) OVER ()` here because it would have to be expressed per dialect, and the page that reads this is one
    * screen an administrator opens, not a hot path.
    */
  def listUsers(
    page: Int,
    pageSize: Int,
    search: Option[String],
    sort: Option[String],
    descending: Boolean,
  ): UIO[UserPage] = {
    for {
      rows  <- userRepo.listPage(Paging.offset(page, pageSize), pageSize, search, sort, descending).orDie
      total <- userRepo.countMatching(search).orDie
    } yield UserPage(rows.map(toDomain), total)
  }

  def getUser(id: Long): IO[AdminFailure, User] = requireUser(id).map(toDomain)

  def createUser(actor: AdminActor, email: String, password: String, isAdmin: Boolean): IO[AdminFailure, User] = {
    val normalizedEmail = email.trim.toLowerCase
    val fieldErrors     = {
      List(
        Validation.validateEmail(normalizedEmail).left.toOption.map("email" -> _),
        Validation.validatePassword(password).left.toOption.map("password" -> _),
      ).flatten.toMap
    }
    for {
      _        <- ZIO.when(fieldErrors.nonEmpty)(ZIO.fail(AdminFailure.ValidationError(fieldErrors)))
      existing <- userRepo.findByEmail(normalizedEmail).orDie
      _        <- ZIO.when(existing.isDefined)(ZIO.fail(AdminFailure.DuplicateEmail))
      hash     <- hasher.hash(password).orDie
      now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
      // An administrator creating an account vouches for the address, so it starts verified — the
      // person never sees a signup form to trigger a verification mail from.
      // The default language, not the acting administrator's: this account belongs to someone else,
      // and nothing about an admin creating it says what language they read. They can change it on
      // first sign-in, and no email goes out here to get wrong in the meantime.
      row      <- userRepo
                    .insert(
                      normalizedEmail,
                      Some(hash),
                      isAdmin,
                      "light",
                      Locale.default.code,
                      now,
                      emailVerifiedAt = Some(now),
                    )
                    .orDie
      _        <- auditTrail.recordUser(
                    actor,
                    AuditAction.userCreate,
                    row.id,
                    s"created user '$normalizedEmail' (id=${row.id}, isAdmin=$isAdmin)",
                  )
    } yield toDomain(row)
  }

  def updateUser(
    actor: AdminActor,
    id: Long,
    email: String,
    isAdmin: Boolean,
    password: Option[String],
  ): IO[AdminFailure, User] = {
    val normalizedEmail = email.trim.toLowerCase
    for {
      before      <- requireUser(id)
      _           <- ZIO
                       .fromEither(Validation.validateEmail(normalizedEmail))
                       .mapError(err => AdminFailure.ValidationError(Map("email" -> err)))
      _           <- ZIO.when(id == actor.userId && !isAdmin)(ZIO.fail(AdminFailure.SelfDemote))
      byEmail     <- userRepo.findByEmail(normalizedEmail).orDie
      _           <- ZIO.when(byEmail.exists(_.id != id))(ZIO.fail(AdminFailure.DuplicateEmail))
      newPassword <-
        password match {
          case Some(pw) if pw.nonEmpty =>
            ZIO
              .fromEither(Validation.validatePassword(pw))
              .mapError(err => AdminFailure.ValidationError(Map("password" -> err)))
              .flatMap(_ => hasher.hash(pw).orDie)
              .map(Some(_))
          case _                       =>
            ZIO.none
        }
      // Profile and password land together or not at all.
      _           <- userRepo.updateProfileAndPassword(id, normalizedEmail, isAdmin, newPassword).orDie
      // A session obtained with the old password must not survive the reset. Sessions live in
      // another repository, so this cannot join the transaction above; it is idempotent and only
      // ever revokes more, which is the safe direction to fail in.
      _           <-
        ZIO.when(newPassword.isDefined) {
          Clock.currentTime(TimeUnit.MILLISECONDS).flatMap(now => sessionRepo.revokeAllForUser(id, now).orDie) *>
            auditTrail.recordUser(
              actor,
              AuditAction.userPasswordReset,
              id,
              s"reset password for user '$normalizedEmail' (id=$id), ending its sessions",
            )
        }
      _           <-
        ZIO.when(before.isAdmin != isAdmin) {
          auditTrail.recordUser(
            actor,
            AuditAction.userUpdate,
            id,
            s"changed admin status of user '$normalizedEmail' (id=$id) to isAdmin=$isAdmin",
          )
        }
      _           <-
        ZIO.when(!before.email.contains(normalizedEmail)) {
          auditTrail.recordUser(
            actor,
            AuditAction.userUpdate,
            id,
            s"changed the address of user id=$id to '$normalizedEmail'",
          )
        }
      updated     <- requireUser(id)
    } yield toDomain(updated)
  }

  def deleteUser(actor: AdminActor, id: Long): IO[AdminFailure, Unit] = {
    for {
      _    <- ZIO.when(id == actor.userId)(ZIO.fail(AdminFailure.SelfDelete))
      user <- requireUser(id)
      _    <- userRepo.deleteById(id).orDie
      _    <- auditTrail.recordUser(actor, AuditAction.userDelete, id, s"deleted user '${user.email}' (id=$id)")
    } yield ()
  }

  private def toSessionInfo(row: SessionRow, now: Long): AdminSessionInfo = {
    AdminSessionInfo(
      createdAt = row.createdAt,
      expiresAt = row.expiresAt,
      revokedAt = row.revokedAt,
      active = row.revokedAt.isEmpty && row.expiresAt > now,
    )
  }

  private def toIdentityInfo(row: OAuthIdentityRow): Option[AdminIdentityInfo] = {
    // A row whose provider no longer parses is one this build has dropped support for. Skipping it keeps the screen
    // renderable, the same way `AuthService.toLinkedIdentity` does for the user's own settings page.
    OAuthProvider.fromString(row.provider).map(provider => AdminIdentityInfo(provider, row.email, row.createdAt))
  }

  private def toTokenInfo(row: EmailVerificationTokenRow, now: Long): AdminVerificationTokenInfo = {
    AdminVerificationTokenInfo(
      createdAt = row.createdAt,
      expiresAt = row.expiresAt,
      consumed = row.consumedAt.isDefined,
      expired = row.expiresAt <= now,
    )
  }

  private def toAttemptEntry(row: LoginAttemptRow): LoginAttemptEntry = {
    LoginAttemptEntry(row.id, row.createdAt, row.email, row.userId, row.ip, row.outcome)
  }

  private def toAuditEntry(row: AuditLogRow): AuditEntry = {
    AuditEntry(
      row.id,
      row.occurredAt,
      row.actorUserId,
      row.actorEmail,
      row.action,
      row.targetType,
      row.targetId,
      row.detail,
      row.ip,
    )
  }

  /** The limiter keys that can keep one account out: its address, its verification budget, and every origin it was
    * recently attempted from.
    *
    * `AuthService.login` blocks when *any* of them trips, so a lockout view that showed only the address key would
    * report "not blocked" for a user who cannot sign in, and a clear button that cleared only that key would not unlock
    * them.
    */
  /** `None` is a guest account: it has no address, so no key can be holding failures against it and there is nothing to
    * clear. Every dimension below is keyed on an address, which a guest never had.
    */
  private def lockoutKeysFor(email: Option[String]): UIO[List[String]] = {
    email match {
      case None          =>
        ZIO.succeed(Nil)
      case Some(address) =>
        for {
          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
          ips <- attemptRepo.distinctRecentIps(address, now - AdminService.lockoutOriginWindow.toMillis).orDie
        } yield RateLimitKey.email(address) :: RateLimitKey.verification(address) :: ips.map(RateLimitKey.ip)
    }
  }

  private def lockoutStatus(email: Option[String]): UIO[LockoutStatus] = {
    for {
      keys    <- lockoutKeysFor(email)
      entries <- ZIO.foreach(keys)(rateLimiter.status)
      blocked  = entries.filter(_.blocked)
      // The address dimension is the one the count on screen refers to; the others only ever add to the blocked set.
      emailKey = entries.headOption
    } yield LockoutStatus(
      blocked = blocked.nonEmpty,
      attempts = emailKey.map(_.attempts).getOrElse(0),
      maxAttempts = RateLimiter.maxAttempts,
      windowMinutes = RateLimiter.window.toMinutes.toInt,
      retryAfterMillis = blocked.map(_.retryAfterMillis).maxOption.getOrElse(0L),
      blockedKeys = blocked.map(_.key),
    )
  }

  def userDetail(id: Long): IO[AdminFailure, AdminUserDetail] = {
    for {
      row        <- requireUser(id)
      now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
      identities <- identityRepo.listForUser(id).orDie
      sessions   <- sessionRepo.listForUser(id).orDie
      tokens     <- tokenRepo.findForUser(id).orDie
      // A guest has no address, so nothing was ever attempted against it — an empty history rather than a query.
      attempts   <- ZIO
                      .foreach(row.email)(email => attemptRepo.recentForEmail(email, AdminService.recentAttemptLimit))
                      .map(_.toList.flatten)
                      .orDie
      lockout    <- lockoutStatus(row.email)
      sessionInfo = sessions.map(toSessionInfo(_, now))
    } yield AdminUserDetail(
      user = toDomain(row),
      hasPassword = row.passwordHash.isDefined,
      emailVerifiedAt = row.emailVerifiedAt,
      identities = identities.flatMap(toIdentityInfo),
      sessions = sessionInfo,
      activeSessions = sessionInfo.count(_.active),
      verificationTokens = tokens.map(toTokenInfo(_, now)),
      lockout = lockout,
      // Keyed on the address rather than the account id, so attempts made against the address before it had an
      // account — or with it misspelt into another account's — are not silently dropped from the picture.
      recentLoginAttempts = attempts.map(toAttemptEntry),
    )
  }

  def userPlays(
    id: Long,
    gameId: Option[Long],
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
  ): IO[AdminFailure, MyPlayPage] = {
    requireUser(id) *> gameService.trackedPlaysOf(id, gameId, page, pageSize, sort, descending)
  }

  def verifyEmailFor(actor: AdminActor, id: Long): IO[AdminFailure, Unit] = {
    for {
      row <- requireUser(id)
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      // Idempotent: confirming an already-confirmed address is a no-op rather than a failure, so a double click on
      // the button is not an error the administrator has to interpret.
      _   <- ZIO.when(row.emailVerifiedAt.isEmpty) {
               userRepo.markEmailVerified(id, now).orDie *>
                 auditTrail.recordUser(
                   actor,
                   AuditAction.userVerifyEmail,
                   id,
                   s"confirmed the email address of user id=$id",
                 )
             }
    } yield ()
  }

  def resendVerificationFor(actor: AdminActor, id: Long): IO[AdminFailure, Unit] = {
    for {
      row <- requireUser(id)
      // Nothing to send to an account with no address; the audit line still records that it was asked for.
      _   <- ZIO.foreachDiscard(row.email) { email =>
               authService.issueVerificationFor(id, email, Locale.fromString(row.locale).getOrElse(Locale.default))
             }
      _   <- auditTrail.recordUser(
               actor,
               AuditAction.userVerificationResend,
               id,
               s"sent a new confirmation link to user id=$id",
             )
    } yield ()
  }

  def revokeSessions(actor: AdminActor, id: Long): IO[AdminFailure, Unit] = {
    for {
      _     <- requireUser(id)
      now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
      count <- sessionRepo.listForUser(id).orDie.map(_.count(s => s.revokedAt.isEmpty && s.expiresAt > now))
      _     <- sessionRepo.revokeAllForUser(id, now).orDie
      _     <- auditTrail.recordUser(actor, AuditAction.userSessionsRevoked, id, s"ended $count session(s) of user id=$id")
    } yield ()
  }

  def unlinkIdentity(actor: AdminActor, id: Long, provider: OAuthProvider): IO[AdminFailure, Unit] = {
    for {
      _ <- requireUser(id)
      // Delegated rather than reimplemented, so the last-credential rule is enforced by the one method that owns it.
      // An administrator is not exempt: the account this would lock out is not theirs.
      _ <- authService
             .unlinkOAuth(id, provider)
             .mapError {
               case AuthFailure.LastCredential =>
                 AdminFailure.LastCredential
               case _                          =>
                 // The only other failure `unlinkOAuth` raises is "not linked", which for a request naming a
                 // provider is the same thing as naming a resource that does not exist.
                 AdminFailure.NotFound
             }
      _ <- auditTrail.recordUser(
             actor,
             AuditAction.userOAuthUnlink,
             id,
             s"detached the ${OAuthProvider.wireName(provider)} identity of user id=$id",
           )
    } yield ()
  }

  def clearLockout(actor: AdminActor, id: Long): IO[AdminFailure, Unit] = {
    for {
      row  <- requireUser(id)
      keys <- lockoutKeysFor(row.email)
      _    <- ZIO.foreachDiscard(keys)(rateLimiter.clear)
      _    <- auditTrail.recordUser(
                actor,
                AuditAction.userLockoutCleared,
                id,
                s"cleared the sign-in lockout of user id=$id (${keys.size} key(s))",
              )
    } yield ()
  }

  def auditLog(
    page: Int,
    pageSize: Int,
    sort: Option[String],
    descending: Boolean,
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): UIO[AuditPage] = {
    for {
      rows  <- auditRepo
                 .list(Paging.offset(page, pageSize), pageSize, sort, descending, action, actorId, targetId)
                 .orDie
      total <- auditRepo.count(action, actorId, targetId).orDie
    } yield AuditPage(rows.map(toAuditEntry), total)
  }

  def loginAttempts(limit: Int, outcome: Option[String]): UIO[List[LoginAttemptEntry]] = {
    attemptRepo.recent(limit, outcome).orDie.map(_.map(toAttemptEntry))
  }

  def rateLimits: UIO[List[RateLimitEntry]] = rateLimiter.snapshot

  def clearRateLimits(actor: AdminActor, key: Option[String]): UIO[Unit] = {
    key match {
      case Some(one) =>
        rateLimiter.clear(one) *>
          auditTrail.recordSystem(actor, AuditAction.systemRateLimitsCleared, "cleared one rate-limit key")
      case None      =>
        rateLimiter.clearAll.flatMap { dropped =>
          auditTrail.recordSystem(
            actor,
            AuditAction.systemRateLimitsCleared,
            s"cleared every rate-limit key ($dropped held)",
          )
        }
    }
  }
}
