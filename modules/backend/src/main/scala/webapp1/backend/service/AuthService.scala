package webapp1.backend.service

import webapp1.backend.db.{SessionRepository, SessionRow, UserRepository, UserRow}
import webapp1.backend.security.{PasswordHasher, SecurityLog, SessionAuth}
import webapp1.shared.domain.{Theme, User}
import webapp1.shared.validation.Validation
import zio.*

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

enum AuthFailure {
  case InvalidCredentials
  case EmailAlreadyRegistered
  case ValidationError(fieldErrors: Map[String, String])
  case RateLimited
  case GoogleAuthFailed(reason: String)
}

trait AuthService {

  /** `clientIp`, when known, is rate-limited alongside the email address. */
  def signup(email: String, password: String, clientIp: Option[String] = None): IO[AuthFailure, (User, String)]
  def login(email: String, password: String, clientIp: Option[String] = None): IO[AuthFailure, (User, String)]
  def loginWithGoogle(identity: GoogleIdentity): IO[AuthFailure, (User, String)]
  def logout(sessionId: String): UIO[Unit]

  /** None both when there's no session and when it's expired/revoked. */
  def currentUser(sessionId: String): UIO[Option[User]]
  def updateTheme(userId: Long, theme: Theme): Task[User]
}

final class AuthServiceLive(
  userRepo: UserRepository,
  sessionRepo: SessionRepository,
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
      row <- userRepo.insert(normalizedEmail, Some(hash), isAdmin = false, googleSubject = None, "light", now).orDie
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

  def loginWithGoogle(identity: GoogleIdentity): IO[AuthFailure, (User, String)] = {
    for {
      // An unverified Google email could belong to someone else (e.g. a domain the
      // attacker doesn't fully control yet) — without this check, matching by email
      // below would let them sign into an existing account they don't own.
      _ <- ZIO.unless(identity.emailVerified)(ZIO.fail(AuthFailure.GoogleAuthFailed("email not verified")))
      maybeBySubject <- userRepo.findByGoogleSubject(identity.subject).orDie
      row <-
        maybeBySubject match {
          case Some(r) =>
            ZIO.succeed(r)
          case None =>
            userRepo
              .findByEmail(identity.email.trim.toLowerCase)
              .orDie
              .flatMap {
                // Account already exists under this email (password signup). Log them
                // in as-is; linking the Google subject to the existing row is a M3
                // follow-up once account-settings UI exists to surface that link.
                case Some(existing) =>
                  ZIO.succeed(existing)
                case None =>
                  Clock
                    .currentTime(TimeUnit.MILLISECONDS)
                    .flatMap { now =>
                      userRepo
                        .insert(
                          identity.email.trim.toLowerCase,
                          None,
                          isAdmin = false,
                          Some(identity.subject),
                          "light",
                          now,
                        )
                        .orDie
                    }
              }
        }
      sessionId <- createSession(row.id)
    } yield (toDomain(row), sessionId)
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

  val live: URLayer[UserRepository & SessionRepository & PasswordHasher & RateLimiter, AuthService] = ZLayer {
    for {
      userRepo <- ZIO.service[UserRepository]
      sessionRepo <- ZIO.service[SessionRepository]
      hasher <- ZIO.service[PasswordHasher]
      rateLimiter <- ZIO.service[RateLimiter]
      timingEqualizerHash <- hasher.hash(timingEqualizerSource).orDie
    } yield new AuthServiceLive(userRepo, sessionRepo, hasher, rateLimiter, timingEqualizerHash): AuthService
  }
}
