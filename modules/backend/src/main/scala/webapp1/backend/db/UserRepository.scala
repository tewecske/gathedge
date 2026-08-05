package webapp1.backend.db

import zio.*

/** Dialect-independent interface. [[PostgresUserRepository]] backs production (Postgres), [[SqliteUserRepository]]
  * backs tests (SQLite) — see the plan's "dual-dialect DB strategy". Both are swapped in purely via ZLayer wiring.
  */
trait UserRepository {
  def insert(
    email: String,
    passwordHash: Option[String],
    isAdmin: Boolean,
    theme: String,
    createdAt: Long,
    emailVerifiedAt: Option[Long],
  ): Task[UserRow]
  def findByEmail(email: String): Task[Option[UserRow]]
  def findById(id: Long): Task[Option[UserRow]]
  def updateTheme(userId: Long, theme: String): Task[Unit]

  /** Idempotent: re-verifying an already-verified account just rewrites the timestamp. */
  def markEmailVerified(userId: Long, verifiedAt: Long): Task[Unit]
  def existsAdmin: Task[Boolean]
  def listAll: Task[List[UserRow]]

  /** Updates email/admin flag only if the row exists. Returns rows affected. */
  def updateProfile(id: Long, email: String, isAdmin: Boolean): Task[Long]
  def updatePasswordHash(id: Long, passwordHash: String): Task[Unit]

  /** Both updates as one unit of work — `passwordHash` of `None` leaves the password alone. Returns rows affected by
    * the profile update.
    */
  def updateProfileAndPassword(id: Long, email: String, isAdmin: Boolean, passwordHash: Option[String]): Task[Long]
  def deleteById(id: Long): Task[Long]
}
