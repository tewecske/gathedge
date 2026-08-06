package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Dialect-independent interface. [[OAuthIdentityRepository.live]] backs production (Postgres),
  * [[OAuthIdentityRepository.test]] backs tests (SQLite) — see the plan's "dual-dialect DB strategy". Both wrap the
  * same [[OAuthIdentityRepositoryLive]] below and are swapped in purely via ZLayer wiring.
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

object OAuthIdentityRepository {
  def findByProviderAndSubject(
    provider: String,
    subject: String,
  ): RIO[OAuthIdentityRepository, Option[OAuthIdentityRow]] =
    ZIO.serviceWithZIO[OAuthIdentityRepository](_.findByProviderAndSubject(provider, subject))

  def listForUser(userId: Long): RIO[OAuthIdentityRepository, List[OAuthIdentityRow]] =
    ZIO.serviceWithZIO[OAuthIdentityRepository](_.listForUser(userId))

  def insert(row: OAuthIdentityRow): RIO[OAuthIdentityRepository, OAuthIdentityRow] =
    ZIO.serviceWithZIO[OAuthIdentityRepository](_.insert(row))

  def deleteByUserAndProvider(userId: Long, provider: String): RIO[OAuthIdentityRepository, Long] =
    ZIO.serviceWithZIO[OAuthIdentityRepository](_.deleteByUserAndProvider(userId, provider))

  val live: ZLayer[DataSource, Nothing, OAuthIdentityRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new OAuthIdentityRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): OAuthIdentityRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, OAuthIdentityRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new OAuthIdentityRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): OAuthIdentityRepository
  )
}

/** The provider's `subject` identifies a person at that provider, so no log line here carries one — see
  * [[QuillRepository.logged]].
  */
final class OAuthIdentityRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with OAuthIdentityRepository {
  import ctx._

  private inline def identities = quote(querySchema[OAuthIdentityRow]("oauth_identities"))

  def findByProviderAndSubject(provider: String, subject: String): Task[Option[OAuthIdentityRow]] = {
    val q = quote(identities.filter(i => i.provider == lift(provider) && i.subject == lift(subject)))
    logged(run(ctx.run(q)).map(_.headOption)) { found =>
      s"oauthIdentities.findByProviderAndSubject provider=$provider found=${found.isDefined}"
    }
  }

  def listForUser(userId: Long): Task[List[OAuthIdentityRow]] = {
    logged(run(ctx.run(quote(identities.filter(_.userId == lift(userId)).sortBy(_.createdAt))))) { rows =>
      s"oauthIdentities.listForUser userId=$userId count=${rows.size}"
    }
  }

  def insert(row: OAuthIdentityRow): Task[OAuthIdentityRow] = {
    val inserted = run(ctx.run(quote(identities.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id))) { identity =>
      s"oauthIdentities.insert id=${identity.id} userId=${row.userId} provider=${row.provider}"
    }
  }

  def deleteByUserAndProvider(userId: Long, provider: String): Task[Long] = {
    val q = quote(identities.filter(i => i.userId == lift(userId) && i.provider == lift(provider)).delete)
    logged(run(ctx.run(q))) { rows =>
      s"oauthIdentities.deleteByUserAndProvider userId=$userId provider=$provider rows=$rows"
    }
  }
}
