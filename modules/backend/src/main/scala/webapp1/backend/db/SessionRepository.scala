package webapp1.backend.db

import zio.*

trait SessionRepository {
  def insert(session: SessionRow): Task[Unit]

  /** Active = not revoked and not past `now` (epoch millis). */
  def findActive(id: String, now: Long): Task[Option[SessionRow]]
  def revoke(id: String, revokedAt: Long): Task[Unit]

  /** Kills every session a user holds. Used when their password changes, so a stolen session can't outlive the
    * credential it was obtained with.
    */
  def revokeAllForUser(userId: Long, revokedAt: Long): Task[Unit]

  /** Drops rows that can never authenticate again (expired, or revoked before `before`). Without this the table only
    * ever grows. Returns the number of rows deleted.
    */
  def deleteExpired(before: Long): Task[Long]
}
