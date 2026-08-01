package webapp1.backend.service

import webapp1.backend.db.{UserRepository, UserRow}
import webapp1.backend.security.PasswordHasher
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

/** Admin actions that change accounts (create/promote-or-demote/delete) are logged to the `security` logger as an audit
  * trail, per summary.md's "logging for application events, such as user actions".
  */
final class AdminServiceLive(userRepo: UserRepository, hasher: PasswordHasher) extends AdminService {

  private val securityLog = org.slf4j.LoggerFactory.getLogger("security")

  private def toDomain(row: UserRow): User = {
    User(row.id, row.email, row.isAdmin, Theme.fromString(row.theme).getOrElse(Theme.Light), row.createdAt.toString)
  }

  private def requireUser(id: Long): IO[AdminFailure, UserRow] = {
    userRepo.findById(id).orDie.flatMap(ZIO.fromOption(_).orElseFail(AdminFailure.NotFound))
  }

  private def audit(actingAdminId: Long, message: String): UIO[Unit] = {
    ZIO.succeed(securityLog.info(s"[admin id=$actingAdminId] $message"))
  }

  def listUsers: UIO[List[User]] = userRepo.listAll.orDie.map(_.map(toDomain))

  def getUser(id: Long): IO[AdminFailure, User] = requireUser(id).map(toDomain)

  def createUser(actingAdminId: Long, email: String, password: String, isAdmin: Boolean): IO[AdminFailure, User] = {
    val normalizedEmail = email.trim.toLowerCase
    val fieldErrors = {
      List(
        Validation.validateEmail(normalizedEmail).left.toOption.map("email" -> _),
        Validation.validatePassword(password).left.toOption.map("password" -> _),
      ).flatten.toMap
    }
    for {
      _ <- ZIO.when(fieldErrors.nonEmpty)(ZIO.fail(AdminFailure.ValidationError(fieldErrors)))
      existing <- userRepo.findByEmail(normalizedEmail).orDie
      _ <- ZIO.when(existing.isDefined)(ZIO.fail(AdminFailure.DuplicateEmail))
      hash <- hasher.hash(password).orDie
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      row <- userRepo.insert(normalizedEmail, Some(hash), isAdmin, None, "light", now).orDie
      _ <- audit(actingAdminId, s"created user '$normalizedEmail' (id=${row.id}, isAdmin=$isAdmin)")
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
      before <- requireUser(id)
      _ <- ZIO
        .fromEither(Validation.validateEmail(normalizedEmail))
        .mapError(err => AdminFailure.ValidationError(Map("email" -> err)))
      _ <- ZIO.when(id == actingAdminId && !isAdmin)(ZIO.fail(AdminFailure.SelfDemote))
      byEmail <- userRepo.findByEmail(normalizedEmail).orDie
      _ <- ZIO.when(byEmail.exists(_.id != id))(ZIO.fail(AdminFailure.DuplicateEmail))
      _ <-
        password match {
          case Some(pw) if pw.nonEmpty =>
            ZIO
              .fromEither(Validation.validatePassword(pw))
              .mapError(err => AdminFailure.ValidationError(Map("password" -> err)))
              .flatMap(_ => hasher.hash(pw).orDie)
              .flatMap(hash => userRepo.updatePasswordHash(id, hash).orDie)
              .flatMap(_ => audit(actingAdminId, s"reset password for user '$normalizedEmail' (id=$id)"))
          case _ =>
            ZIO.unit
        }
      _ <-
        ZIO.when(before.isAdmin != isAdmin) {
          audit(actingAdminId, s"changed admin status of user '$normalizedEmail' (id=$id) to isAdmin=$isAdmin")
        }
      _ <- userRepo.updateProfile(id, normalizedEmail, isAdmin).orDie
      updated <- requireUser(id)
    } yield toDomain(updated)
  }

  def deleteUser(actingAdminId: Long, id: Long): IO[AdminFailure, Unit] = {
    for {
      _ <- ZIO.when(id == actingAdminId)(ZIO.fail(AdminFailure.SelfDelete))
      user <- requireUser(id)
      _ <- userRepo.deleteById(id).orDie
      _ <- audit(actingAdminId, s"deleted user '${user.email}' (id=$id)")
    } yield ()
  }
}

object AdminServiceLive {
  val live: URLayer[UserRepository & PasswordHasher, AdminService] = ZLayer.fromFunction(
    (u: UserRepository, h: PasswordHasher) => new AdminServiceLive(u, h): AdminService
  )
}
