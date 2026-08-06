package webapp1.backend.service

import webapp1.backend.db.{SessionRepository, UserRepository, UserRow}
import webapp1.backend.security.{PasswordHasher, SecurityLog}
import webapp1.shared.domain.{Theme, User}
import webapp1.shared.validation.Validation
import zio.*

import java.util.concurrent.TimeUnit

enum AdminFailure {
  case ValidationError(fieldErrors: Map[String, String])
  case DuplicateEmail
  case NotFound

  /** An administrator can't remove their own admin privileges or delete their own account — enforced here independent
    * of the UI (summary.md).
    */
  case SelfDemote
  case SelfDelete
}

trait AdminService {
  def listUsers: UIO[List[User]]
  def getUser(id: Long): IO[AdminFailure, User]
  def createUser(actingAdminId: Long, email: String, password: String, isAdmin: Boolean): IO[AdminFailure, User]
  def updateUser(
    actingAdminId: Long,
    id: Long,
    email: String,
    isAdmin: Boolean,
    password: Option[String],
  ): IO[AdminFailure, User]
  def deleteUser(actingAdminId: Long, id: Long): IO[AdminFailure, Unit]
}

object AdminService {
  def listUsers: URIO[AdminService, List[User]] =
    ZIO.serviceWithZIO[AdminService](_.listUsers)

  def getUser(id: Long): ZIO[AdminService, AdminFailure, User] =
    ZIO.serviceWithZIO[AdminService](_.getUser(id))

  def createUser(
    actingAdminId: Long,
    email: String,
    password: String,
    isAdmin: Boolean,
  ): ZIO[AdminService, AdminFailure, User] =
    ZIO.serviceWithZIO[AdminService](_.createUser(actingAdminId, email, password, isAdmin))

  def updateUser(
    actingAdminId: Long,
    id: Long,
    email: String,
    isAdmin: Boolean,
    password: Option[String],
  ): ZIO[AdminService, AdminFailure, User] =
    ZIO.serviceWithZIO[AdminService](_.updateUser(actingAdminId, id, email, isAdmin, password))

  def deleteUser(actingAdminId: Long, id: Long): ZIO[AdminService, AdminFailure, Unit] =
    ZIO.serviceWithZIO[AdminService](_.deleteUser(actingAdminId, id))
}

/** Admin actions that change accounts (create/promote-or-demote/delete) are logged to the `security` logger as an audit
  * trail, per summary.md's "logging for application events, such as user actions".
  */
final class AdminServiceLive(userRepo: UserRepository, sessionRepo: SessionRepository, hasher: PasswordHasher)
    extends AdminService {

  private def toDomain(row: UserRow): User = {
    User(
      row.id,
      row.email,
      row.isAdmin,
      Theme.fromString(row.theme).getOrElse(Theme.Light),
      row.createdAt.toString,
      row.emailVerifiedAt.isDefined,
    )
  }

  private def requireUser(id: Long): IO[AdminFailure, UserRow] = {
    userRepo.findById(id).orDie.flatMap(ZIO.fromOption(_).orElseFail(AdminFailure.NotFound))
  }

  private def audit(actingAdminId: Long, message: String): UIO[Unit] = {
    SecurityLog.info(s"[admin id=$actingAdminId] $message")
  }

  def listUsers: UIO[List[User]] = userRepo.listAll.orDie.map(_.map(toDomain))

  def getUser(id: Long): IO[AdminFailure, User] = requireUser(id).map(toDomain)

  def createUser(actingAdminId: Long, email: String, password: String, isAdmin: Boolean): IO[AdminFailure, User] = {
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
      row      <- userRepo.insert(normalizedEmail, Some(hash), isAdmin, "light", now, emailVerifiedAt = Some(now)).orDie
      _        <- audit(actingAdminId, s"created user '$normalizedEmail' (id=${row.id}, isAdmin=$isAdmin)")
    } yield toDomain(row)
  }

  def updateUser(
    actingAdminId: Long,
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
      _           <- ZIO.when(id == actingAdminId && !isAdmin)(ZIO.fail(AdminFailure.SelfDemote))
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
            audit(actingAdminId, s"reset password for user '$normalizedEmail' (id=$id)")
        }
      _           <-
        ZIO.when(before.isAdmin != isAdmin) {
          audit(actingAdminId, s"changed admin status of user '$normalizedEmail' (id=$id) to isAdmin=$isAdmin")
        }
      updated     <- requireUser(id)
    } yield toDomain(updated)
  }

  def deleteUser(actingAdminId: Long, id: Long): IO[AdminFailure, Unit] = {
    for {
      _    <- ZIO.when(id == actingAdminId)(ZIO.fail(AdminFailure.SelfDelete))
      user <- requireUser(id)
      _    <- userRepo.deleteById(id).orDie
      _    <- audit(actingAdminId, s"deleted user '${user.email}' (id=$id)")
    } yield ()
  }
}

object AdminServiceLive {
  val live: URLayer[UserRepository & SessionRepository & PasswordHasher, AdminService] = ZLayer.fromFunction(
    (u: UserRepository, s: SessionRepository, h: PasswordHasher) => new AdminServiceLive(u, s, h): AdminService
  )
}
