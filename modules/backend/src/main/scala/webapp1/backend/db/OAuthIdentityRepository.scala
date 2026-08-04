package webapp1.backend.db

import zio.*

/** Dialect-independent interface. [[PostgresOAuthIdentityRepository]] backs production (Postgres),
  * [[SqliteOAuthIdentityRepository]] backs tests (SQLite) — see the plan's "dual-dialect DB strategy". Both are swapped
  * in purely via ZLayer wiring.
  */
trait OAuthIdentityRepository {

  /** The only lookup that may decide *which account* a social sign-in lands in. Matching on `email` instead is the
    * account-takeover path this table exists to avoid.
    */
  def findByProviderAndSubject(provider: String, subject: String): Task[Option[OAuthIdentityRow]]
  def listForUser(userId: Long): Task[List[OAuthIdentityRow]]
  def insert(row: OAuthIdentityRow): Task[OAuthIdentityRow]

  /** Returns rows affected, so a caller can tell "unlinked" from "there was nothing to unlink". */
  def deleteByUserAndProvider(userId: Long, provider: String): Task[Long]
}
