package webapp1.backend.db

import zio.*

/** Single-use proof-of-address tokens issued at signup and on resend. Same dual-dialect shape as every other repository
  * here: one generic implementation, two thin `ZLayer` objects.
  */
trait EmailVerificationTokenRepository {
  def insert(row: EmailVerificationTokenRow): Task[EmailVerificationTokenRow]
  def findByToken(token: String): Task[Option[EmailVerificationTokenRow]]
  def markConsumed(token: String, consumedAt: Long): Task[Unit]

  /** Invalidates whatever is outstanding for an account, so a resend leaves exactly one live token. */
  def deleteForUser(userId: Long): Task[Long]
  def deleteExpired(before: Long): Task[Long]
}
