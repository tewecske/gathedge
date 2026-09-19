package gathedge.backend.db

import io.getquill.*
import zio.*

import javax.sql.DataSource

/** Single-use tokens confirming a change of the account's own address, issued from the settings page. */
trait EmailChangeTokenRepository {
  def insert(row: EmailChangeTokenRow): Task[EmailChangeTokenRow]
  def findByToken(token: String): Task[Option[EmailChangeTokenRow]]
  def markConsumed(token: String, consumedAt: Long): Task[Unit]

  /** Invalidates whatever is outstanding for an account, so a fresh request leaves exactly one live token. */
  def deleteForUser(userId: Long): Task[Long]
  def deleteExpired(before: Long): Task[Long]
}

object EmailChangeTokenRepository {
  def insert(row: EmailChangeTokenRow): RIO[EmailChangeTokenRepository, EmailChangeTokenRow] =
    ZIO.serviceWithZIO[EmailChangeTokenRepository](_.insert(row))

  def findByToken(token: String): RIO[EmailChangeTokenRepository, Option[EmailChangeTokenRow]] =
    ZIO.serviceWithZIO[EmailChangeTokenRepository](_.findByToken(token))

  def markConsumed(token: String, consumedAt: Long): RIO[EmailChangeTokenRepository, Unit] =
    ZIO.serviceWithZIO[EmailChangeTokenRepository](_.markConsumed(token, consumedAt))

  def deleteForUser(userId: Long): RIO[EmailChangeTokenRepository, Long] =
    ZIO.serviceWithZIO[EmailChangeTokenRepository](_.deleteForUser(userId))

  def deleteExpired(before: Long): RIO[EmailChangeTokenRepository, Long] =
    ZIO.serviceWithZIO[EmailChangeTokenRepository](_.deleteExpired(before))

  val live: ZLayer[DataSource, Nothing, EmailChangeTokenRepository] =
    ZLayer.fromFunction((ds: DataSource) => new EmailChangeTokenRepositoryLive(ds): EmailChangeTokenRepository)
}

/** Confirmation tokens are bearer credentials, so no log line here carries one — see [[QuillRepository.logged]]. Nor
  * does one carry `new_email`, for the same reason a log line never carries an address.
  */
final class EmailChangeTokenRepositoryLive(dataSource: DataSource)
    extends QuillRepository(dataSource, new PostgresZioJdbcContext(SnakeCase))
    with EmailChangeTokenRepository {
  import ctx._

  private inline def tokens = quote(querySchema[EmailChangeTokenRow]("email_change_tokens"))

  def insert(row: EmailChangeTokenRow): Task[EmailChangeTokenRow] = {
    val inserted = run(ctx.run(quote(tokens.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id))) { token =>
      s"emailChangeTokens.insert id=${token.id} userId=${row.userId} expiresAt=${row.expiresAt}"
    }
  }

  def findByToken(token: String): Task[Option[EmailChangeTokenRow]] = {
    logged(run(ctx.run(quote(tokens.filter(_.token == lift(token))))).map(_.headOption)) { found =>
      s"emailChangeTokens.findByToken found=${found.isDefined}"
    }
  }

  def markConsumed(token: String, consumedAt: Long): Task[Unit] = {
    val q = quote(tokens.filter(_.token == lift(token)).update(_.consumedAt -> lift(Option(consumedAt))))
    logged(run(ctx.run(q)).unit)(_ => "emailChangeTokens.markConsumed")
  }

  def deleteForUser(userId: Long): Task[Long] = {
    logged(run(ctx.run(quote(tokens.filter(_.userId == lift(userId)).delete)))) { rows =>
      s"emailChangeTokens.deleteForUser userId=$userId rows=$rows"
    }
  }

  def deleteExpired(before: Long): Task[Long] = {
    logged(run(ctx.run(quote(tokens.filter(_.expiresAt < lift(before)).delete)))) { rows =>
      s"emailChangeTokens.deleteExpired rows=$rows"
    }
  }
}
