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
    googleSubject: Option[String],
    theme: String,
    createdAt: Long,
  ): Task[UserRow]
  def findByEmail(email: String): Task[Option[UserRow]]
  def findById(id: Long): Task[Option[UserRow]]
  def findByGoogleSubject(googleSubject: String): Task[Option[UserRow]]
  def updateTheme(userId: Long, theme: String): Task[Unit]
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
