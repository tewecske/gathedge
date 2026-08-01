package webapp1.backend.db

import zio.*

trait SessionRepository {
  def insert(session: SessionRow): Task[Unit]

  /** Active = not revoked and not past `now` (epoch millis). */
  def findActive(id: String, now: Long): Task[Option[SessionRow]]
  def revoke(id: String, revokedAt: Long): Task[Unit]
}
