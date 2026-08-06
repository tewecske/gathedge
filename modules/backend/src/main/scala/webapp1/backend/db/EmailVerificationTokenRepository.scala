package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Single-use proof-of-address tokens issued at signup and on resend. Same dual-dialect shape as every other repository
  * here: one generic implementation, two thin `ZLayer`s on the companion.
  */
trait EmailVerificationTokenRepository {
  def insert(row: EmailVerificationTokenRow): Task[EmailVerificationTokenRow]
  def findByToken(token: String): Task[Option[EmailVerificationTokenRow]]
  def markConsumed(token: String, consumedAt: Long): Task[Unit]

  /** Invalidates whatever is outstanding for an account, so a resend leaves exactly one live token. */
  def deleteForUser(userId: Long): Task[Long]
  def deleteExpired(before: Long): Task[Long]
}

object EmailVerificationTokenRepository {
  def insert(row: EmailVerificationTokenRow): RIO[EmailVerificationTokenRepository, EmailVerificationTokenRow] =
    ZIO.serviceWithZIO[EmailVerificationTokenRepository](_.insert(row))

  def findByToken(token: String): RIO[EmailVerificationTokenRepository, Option[EmailVerificationTokenRow]] =
    ZIO.serviceWithZIO[EmailVerificationTokenRepository](_.findByToken(token))

  def markConsumed(token: String, consumedAt: Long): RIO[EmailVerificationTokenRepository, Unit] =
    ZIO.serviceWithZIO[EmailVerificationTokenRepository](_.markConsumed(token, consumedAt))

  def deleteForUser(userId: Long): RIO[EmailVerificationTokenRepository, Long] =
    ZIO.serviceWithZIO[EmailVerificationTokenRepository](_.deleteForUser(userId))

  def deleteExpired(before: Long): RIO[EmailVerificationTokenRepository, Long] =
    ZIO.serviceWithZIO[EmailVerificationTokenRepository](_.deleteExpired(before))

  val live: ZLayer[DataSource, Nothing, EmailVerificationTokenRepository] = ZLayer.fromFunction((ds: DataSource) => {
    new EmailVerificationTokenRepositoryLive(
      ds,
      new PostgresZioJdbcContext(SnakeCase),
    ): EmailVerificationTokenRepository
  })

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, EmailVerificationTokenRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new EmailVerificationTokenRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): EmailVerificationTokenRepository
  )
}

/** Verification tokens are bearer credentials, so no log line here carries one — see [[QuillRepository.logged]]. */
final class EmailVerificationTokenRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with EmailVerificationTokenRepository {
  import ctx._

  private inline def tokens = quote(querySchema[EmailVerificationTokenRow]("email_verification_tokens"))

  def insert(row: EmailVerificationTokenRow): Task[EmailVerificationTokenRow] = {
    val inserted = run(ctx.run(quote(tokens.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id))) { token =>
      s"emailVerificationTokens.insert id=${token.id} userId=${row.userId} expiresAt=${row.expiresAt}"
    }
  }

  def findByToken(token: String): Task[Option[EmailVerificationTokenRow]] = {
    logged(run(ctx.run(quote(tokens.filter(_.token == lift(token))))).map(_.headOption)) { found =>
      s"emailVerificationTokens.findByToken found=${found.isDefined}"
    }
  }

  def markConsumed(token: String, consumedAt: Long): Task[Unit] = {
    val q = quote(tokens.filter(_.token == lift(token)).update(_.consumedAt -> lift(Option(consumedAt))))
    logged(run(ctx.run(q)).unit)(_ => "emailVerificationTokens.markConsumed")
  }

  def deleteForUser(userId: Long): Task[Long] = {
    logged(run(ctx.run(quote(tokens.filter(_.userId == lift(userId)).delete)))) { rows =>
      s"emailVerificationTokens.deleteForUser userId=$userId rows=$rows"
    }
  }

  def deleteExpired(before: Long): Task[Long] = {
    logged(run(ctx.run(quote(tokens.filter(_.expiresAt < lift(before)).delete)))) { rows =>
      s"emailVerificationTokens.deleteExpired rows=$rows"
    }
  }
}
