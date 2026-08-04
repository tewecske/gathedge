package webapp1.backend.service

import webapp1.backend.db.{
  OAuthIdentityRepository,
  OAuthIdentityRow,
  SessionRepository,
  SessionRow,
  UserRepository,
  UserRow,
}
import webapp1.backend.security.{PasswordHasher, SecurityLog, SessionAuth}
import webapp1.shared.domain.{OAuthProvider, Theme, User}
import webapp1.shared.dto.LinkedIdentity
import webapp1.shared.validation.Validation
import zio.*

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

import OAuthProvider.wire

enum AuthFailure {
  case InvalidCredentials
  case EmailAlreadyRegistered
  case ValidationError(fieldErrors: Map[String, String])
  case RateLimited
  case OAuthFailed(reason: String)

  /** A social sign-in succeeded at the provider, but its email already belongs to an account that has never linked that
    * provider. Deliberately not a login: see [[AuthService.loginWithOAuth]].
    */
  case OAuthAccountExists(provider: OAuthProvider)

  /** That provider identity is already attached to some account — this one, or somebody else's. */
  case OAuthAlreadyLinked

  /** Unlinking would leave the account with no way to sign in at all. */
  case LastCredential
}

trait AuthService {

  /** `clientIp`, when known, is rate-limited alongside the email address. */
  def signup(email: String, password: String, clientIp: Option[String] = None): IO[AuthFailure, (User, String)]
  def login(email: String, password: String, clientIp: Option[String] = None): IO[AuthFailure, (User, String)]

  /** Signs in — or registers — the person behind a verified provider identity.
    *
    * Matching is on `(provider, subject)` only. When that misses but the email is already taken, this **fails** with
    * [[AuthFailure.OAuthAccountExists]] rather than logging into the existing account: any provider that lets a user
    * present an address they do not control would otherwise be an account-takeover path, and no amount of
    * `email_verified` checking generalises safely across providers. The recovery route is to sign in normally and link
    * the provider from the settings page, which is what [[linkOAuth]] is for.
    */
  def loginWithOAuth(identity: OAuthIdentity): IO[AuthFailure, (User, String)]

  /** Attaches a provider identity to an already-authenticated user. */
  def linkOAuth(userId: Long, identity: OAuthIdentity): IO[AuthFailure, Unit]

  /** Detaches one, unless it is the account's last remaining credential. */
  def unlinkOAuth(userId: Long, provider: OAuthProvider): IO[AuthFailure, Unit]
  def listIdentities(userId: Long): UIO[List[LinkedIdentity]]
  def hasPassword(userId: Long): UIO[Boolean]

  /** `currentPassword` is verified only when the account already has one; a social-only account is setting its first.
    */
  def setPassword(userId: Long, currentPassword: Option[String], newPassword: String): IO[AuthFailure, Unit]
  def logout(sessionId: String): UIO[Unit]

  /** None both when there's no session and when it's expired/revoked. */
  def currentUser(sessionId: String): UIO[Option[User]]
  def updateTheme(userId: Long, theme: Theme): Task[User]
}

final class AuthServiceLive(
  userRepo: UserRepository,
  sessionRepo: SessionRepository,
  identityRepo: OAuthIdentityRepository,
  hasher: PasswordHasher,
  rateLimiter: RateLimiter,
  /** Hash of a fixed throwaway string, verified against on the login paths that have no real hash to check, so that "no
    * such account" costs the same as "wrong password". Skipping the bcrypt work there made response time a reliable
    * oracle for which email addresses have accounts.
    */
  timingEqualizerHash: String,
) extends AuthService {

  private val secureRandom = new SecureRandom()

  private def logRateLimited(email: String): UIO[Unit] = {
    SecurityLog.warn(s"Rate limit exceeded for '$email'")
  }

  private def logFailedAttempt(email: String, reason: String): UIO[Unit] = {
    SecurityLog.warn(s"Failed auth attempt for '$email': $reason")
  }

  private def newSessionId(): UIO[String] = {
    ZIO.succeed {
      val bytes = new Array[Byte](32)
      secureRandom.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    }
  }

  private def toDomain(row: UserRow): User = {
    User(row.id, row.email, row.isAdmin, Theme.fromString(row.theme).getOrElse(Theme.Light), row.createdAt.toString)
  }

  private def toLinkedIdentity(row: OAuthIdentityRow): Option[LinkedIdentity] = {
    // A row whose provider no longer parses is one this build has dropped support for. Skipping it keeps
    // the settings page renderable; the row stays in the table for whoever removed the case to deal with.
    OAuthProvider.fromString(row.provider).map(p => LinkedIdentity(p, row.email, row.createdAt.toString))
  }

  private def createSession(userId: Long): UIO[String] = {
    for {
      id <- newSessionId()
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ <- sessionRepo.insert(SessionRow(id, userId, now, now + SessionAuth.sessionDuration.toMillis, None)).orDie
    } yield id
  }

  private def validateCredentials(email: String, password: String): IO[AuthFailure, Unit] = {
    val errors = {
      List(
        Validation.validateEmail(email).left.toOption.map("email" -> _),
        Validation.validatePassword(password).left.toOption.map("password" -> _),
      ).flatten.toMap
    }
    if (errors.nonEmpty)
      ZIO.fail(AuthFailure.ValidationError(errors))
    else
      ZIO.unit
  }

  private def rateLimitKeys(normalizedEmail: String, clientIp: Option[String]): List[String] = {
    RateLimitKey.email(normalizedEmail) :: clientIp.map(RateLimitKey.ip).toList
  }

  private def anyKeyBlocked(keys: List[String]): UIO[Boolean] = {
    ZIO.exists(keys)(rateLimiter.isBlocked)
  }

  private def recordFailure(keys: List[String]): UIO[Unit] = {
    ZIO.foreachDiscard(keys)(rateLimiter.recordFailure)
  }

  private def clearFailures(keys: List[String]): UIO[Unit] = {
    ZIO.foreachDiscard(keys)(rateLimiter.clear)
  }

  /** Burns the same bcrypt work a real verification would, so failing early can't be timed. */
  private def equalizeTiming: UIO[Unit] = {
    hasher.verify("", timingEqualizerHash).orDie.unit
  }

  def signup(email: String, password: String, clientIp: Option[String]): IO[AuthFailure, (User, String)] = {
    val normalizedEmail = email.trim.toLowerCase
    val keys = rateLimitKeys(normalizedEmail, clientIp)
    for {
      _ <- validateCredentials(normalizedEmail, password)
      blocked <- anyKeyBlocked(keys)
      _ <- ZIO.when(blocked)(logRateLimited(normalizedEmail) *> ZIO.fail(AuthFailure.RateLimited))
      existing <- userRepo.findByEmail(normalizedEmail).orDie
      _ <-
        ZIO.when(existing.isDefined) {
          recordFailure(keys) *> ZIO.fail(AuthFailure.EmailAlreadyRegistered)
        }
      hash <- hasher.hash(password).orDie
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row <- userRepo.insert(normalizedEmail, Some(hash), isAdmin = false, "light", now).orDie
      sessionId <- createSession(row.id)
      _ <- clearFailures(keys)
    } yield (toDomain(row), sessionId)
  }

  def login(email: String, password: String, clientIp: Option[String]): IO[AuthFailure, (User, String)] = {
    val normalizedEmail = email.trim.toLowerCase
    val keys = rateLimitKeys(normalizedEmail, clientIp)
    for {
      blocked <- anyKeyBlocked(keys)
      _ <- ZIO.when(blocked)(logRateLimited(normalizedEmail) *> ZIO.fail(AuthFailure.RateLimited))
      maybeRow <- userRepo.findByEmail(normalizedEmail).orDie
      row <-
        maybeRow match {
          case None =>
            equalizeTiming *> recordFailure(keys) *> logFailedAttempt(normalizedEmail, "no such account") *>
              ZIO.fail(AuthFailure.InvalidCredentials)
          case Some(r) =>
            ZIO.succeed(r)
        }
      _ <-
        row.passwordHash match {
          case None =>
            equalizeTiming *> recordFailure(keys) *>
              logFailedAttempt(normalizedEmail, "no password set (Google-only account)") *>
              ZIO.fail(AuthFailure.InvalidCredentials)
          case Some(hash) =>
            hasher
              .verify(password, hash)
              .orDie
              .flatMap { ok =>
                if (ok)
                  ZIO.unit
                else {
                  recordFailure(keys) *> logFailedAttempt(normalizedEmail, "wrong password") *>
                    ZIO.fail(AuthFailure.InvalidCredentials)
                }
              }
        }
      sessionId <- createSession(row.id)
      _ <- clearFailures(keys)
    } yield (toDomain(row), sessionId)
  }

  private def insertIdentity(userId: Long, identity: OAuthIdentity): UIO[Unit] = {
    for {
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _ <-
        identityRepo
          .insert(OAuthIdentityRow(0L, userId, identity.provider.wire, identity.subject, Some(identity.email), now))
          .orDie
    } yield ()
  }

  def loginWithOAuth(identity: OAuthIdentity): IO[AuthFailure, (User, String)] = {
    val normalizedEmail = identity.email.trim.toLowerCase
    for {
      existingLink <- identityRepo.findByProviderAndSubject(identity.provider.wire, identity.subject).orDie
      row <-
        existingLink match {
          case Some(link) =>
            // The link is a FK onto users with ON DELETE CASCADE, so a link with no user is a broken
            // invariant rather than a request the caller got wrong.
            userRepo
              .findById(link.userId)
              .orDie
              .someOrElseZIO(
                ZIO.die(new IllegalStateException(s"oauth_identities row ${link.id} points at a missing user"))
              )
          case None =>
            userRepo
              .findByEmail(normalizedEmail)
              .orDie
              .flatMap {
                case Some(_) =>
                  SecurityLog.warn(
                    s"Refused ${identity.provider.wire} sign-in for '$normalizedEmail': " +
                      "the email belongs to an account with no such linked identity"
                  ) *> ZIO.fail(AuthFailure.OAuthAccountExists(identity.provider))
                case None =>
                  for {
                    now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                    created <- userRepo.insert(normalizedEmail, None, isAdmin = false, "light", now).orDie
                    _ <- insertIdentity(created.id, identity)
                    _ <- SecurityLog.info(
                      s"Account created via ${identity.provider.wire} sign-in for '$normalizedEmail'"
                    )
                  } yield created
              }
        }
      sessionId <- createSession(row.id)
    } yield (toDomain(row), sessionId)
  }

  def linkOAuth(userId: Long, identity: OAuthIdentity): IO[AuthFailure, Unit] = {
    for {
      existingLink <- identityRepo.findByProviderAndSubject(identity.provider.wire, identity.subject).orDie
      _ <- ZIO.when(existingLink.isDefined)(ZIO.fail(AuthFailure.OAuthAlreadyLinked))
      mine <- identityRepo.listForUser(userId).orDie
      // One identity per provider per account: two Google accounts on one login would make the
      // settings page's unlink-by-provider ambiguous.
      _ <- ZIO.when(mine.exists(_.provider == identity.provider.wire))(ZIO.fail(AuthFailure.OAuthAlreadyLinked))
      _ <- insertIdentity(userId, identity)
      _ <- SecurityLog.info(s"Linked ${identity.provider.wire} identity to user $userId")
    } yield ()
  }

  def unlinkOAuth(userId: Long, provider: OAuthProvider): IO[AuthFailure, Unit] = {
    for {
      mine <- identityRepo.listForUser(userId).orDie
      _ <- ZIO.unless(mine.exists(_.provider == provider.wire))(ZIO.fail(AuthFailure.OAuthFailed("not linked")))
      hasPw <- hasPassword(userId)
      // Without this an account whose only credential is the identity being removed becomes
      // permanently unreachable — there is no password to fall back to and no other provider.
      _ <- ZIO.when(!hasPw && mine.sizeIs <= 1)(ZIO.fail(AuthFailure.LastCredential))
      _ <- identityRepo.deleteByUserAndProvider(userId, provider.wire).orDie
      _ <- SecurityLog.info(s"Unlinked ${provider.wire} identity from user $userId")
    } yield ()
  }

  def listIdentities(userId: Long): UIO[List[LinkedIdentity]] = {
    identityRepo.listForUser(userId).orDie.map(_.flatMap(toLinkedIdentity))
  }

  def hasPassword(userId: Long): UIO[Boolean] = {
    userRepo.findById(userId).orDie.map(_.exists(_.passwordHash.isDefined))
  }

  def setPassword(userId: Long, currentPassword: Option[String], newPassword: String): IO[AuthFailure, Unit] = {
    for {
      row <- userRepo
        .findById(userId)
        .orDie
        .someOrElseZIO(ZIO.die(new IllegalStateException(s"user $userId not found")))
      _ <- ZIO
        .fromEither(Validation.validatePassword(newPassword))
        .mapError(message => AuthFailure.ValidationError(Map("newPassword" -> message)))
      _ <-
        row.passwordHash match {
          case None =>
            // No password yet, so there is nothing to prove: the session cookie is the authorisation.
            ZIO.unit
          case Some(existing) =>
            currentPassword match {
              case None =>
                ZIO.fail(AuthFailure.ValidationError(Map("currentPassword" -> "Enter your current password")))
              case Some(supplied) =>
                hasher
                  .verify(supplied, existing)
                  .orDie
                  .flatMap { ok =>
                    if (ok)
                      ZIO.unit
                    else {
                      logFailedAttempt(row.email, "wrong current password on password change") *>
                        ZIO.fail(AuthFailure.ValidationError(Map("currentPassword" -> "Incorrect password")))
                    }
                  }
            }
        }
      hash <- hasher.hash(newPassword).orDie
      _ <- userRepo.updatePasswordHash(userId, hash).orDie
      _ <- SecurityLog.info(
        s"Password ${
            if (row.passwordHash.isDefined)
              "changed"
            else
              "set"
          } for user $userId"
      )
    } yield ()
  }

  def logout(sessionId: String): UIO[Unit] = {
    Clock.currentTime(TimeUnit.MILLISECONDS).flatMap(now => sessionRepo.revoke(sessionId, now).orDie)
  }

  def currentUser(sessionId: String): UIO[Option[User]] = {
    (
      for {
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        maybeSession <- sessionRepo.findActive(sessionId, now)
        maybeUser <-
          maybeSession match {
            case None =>
              ZIO.none
            case Some(s) =>
              userRepo.findById(s.userId)
          }
      } yield maybeUser.map(toDomain)
    ).orDie
  }

  def updateTheme(userId: Long, theme: Theme): Task[User] = {
    for {
      _ <- userRepo.updateTheme(userId, theme.toString.toLowerCase)
      row <- userRepo.findById(userId).someOrFail(new RuntimeException(s"user $userId not found"))
    } yield toDomain(row)
  }
}

object AuthServiceLive {

  /** Hashed once at startup rather than kept as a literal, so the work factor always matches whatever
    * [[PasswordHasher]] is configured with.
    */
  private val timingEqualizerSource = "account-enumeration-guard"

  val live: URLayer[
    UserRepository & SessionRepository & OAuthIdentityRepository & PasswordHasher & RateLimiter,
    AuthService,
  ] = ZLayer {
    for {
      userRepo <- ZIO.service[UserRepository]
      sessionRepo <- ZIO.service[SessionRepository]
      identityRepo <- ZIO.service[OAuthIdentityRepository]
      hasher <- ZIO.service[PasswordHasher]
      rateLimiter <- ZIO.service[RateLimiter]
      timingEqualizerHash <- hasher.hash(timingEqualizerSource).orDie
    } yield new AuthServiceLive(
      userRepo,
      sessionRepo,
      identityRepo,
      hasher,
      rateLimiter,
      timingEqualizerHash,
    ): AuthService
  }
}
