package gathedge.backend.db

import io.getquill.*
import zio.*

import javax.sql.DataSource

/** Single-use "forgot password" links. */
trait PasswordResetTokenRepository {
  def insert(row: PasswordResetTokenRow): Task[PasswordResetTokenRow]
  def findByToken(token: String): Task[Option[PasswordResetTokenRow]]
  def markConsumed(token: String, consumedAt: Long): Task[Unit]

  /** Invalidates whatever is outstanding for an account, so a fresh request leaves exactly one live token. */
  def deleteForUser(userId: Long): Task[Long]
  def deleteExpired(before: Long): Task[Long]
}

object PasswordResetTokenRepository {
  def insert(row: PasswordResetTokenRow): RIO[PasswordResetTokenRepository, PasswordResetTokenRow] =
    ZIO.serviceWithZIO[PasswordResetTokenRepository](_.insert(row))

  def findByToken(token: String): RIO[PasswordResetTokenRepository, Option[PasswordResetTokenRow]] =
    ZIO.serviceWithZIO[PasswordResetTokenRepository](_.findByToken(token))

  def markConsumed(token: String, consumedAt: Long): RIO[PasswordResetTokenRepository, Unit] =
    ZIO.serviceWithZIO[PasswordResetTokenRepository](_.markConsumed(token, consumedAt))

  def deleteForUser(userId: Long): RIO[PasswordResetTokenRepository, Long] =
    ZIO.serviceWithZIO[PasswordResetTokenRepository](_.deleteForUser(userId))

  def deleteExpired(before: Long): RIO[PasswordResetTokenRepository, Long] =
    ZIO.serviceWithZIO[PasswordResetTokenRepository](_.deleteExpired(before))

  val live: ZLayer[DataSource, Nothing, PasswordResetTokenRepository] =
    ZLayer.fromFunction((ds: DataSource) => new PasswordResetTokenRepositoryLive(ds): PasswordResetTokenRepository)
}

/** Reset tokens are bearer credentials, so no log line here carries one — see [[QuillRepository.logged]]. */
final class PasswordResetTokenRepositoryLive(dataSource: DataSource)
    extends QuillRepository(dataSource, new PostgresZioJdbcContext(SnakeCase))
    with PasswordResetTokenRepository {
  import ctx._

  private inline def tokens = quote(querySchema[PasswordResetTokenRow]("password_reset_tokens"))

  def insert(row: PasswordResetTokenRow): Task[PasswordResetTokenRow] = {
    val inserted = run(ctx.run(quote(tokens.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id))) { token =>
      s"passwordResetTokens.insert id=${token.id} userId=${row.userId} expiresAt=${row.expiresAt}"
    }
  }

  def findByToken(token: String): Task[Option[PasswordResetTokenRow]] = {
    logged(run(ctx.run(quote(tokens.filter(_.token == lift(token))))).map(_.headOption)) { found =>
      s"passwordResetTokens.findByToken found=${found.isDefined}"
    }
  }

  def markConsumed(token: String, consumedAt: Long): Task[Unit] = {
    val q = quote(tokens.filter(_.token == lift(token)).update(_.consumedAt -> lift(Option(consumedAt))))
    logged(run(ctx.run(q)).unit)(_ => "passwordResetTokens.markConsumed")
  }

  def deleteForUser(userId: Long): Task[Long] = {
    logged(run(ctx.run(quote(tokens.filter(_.userId == lift(userId)).delete)))) { rows =>
      s"passwordResetTokens.deleteForUser userId=$userId rows=$rows"
    }
  }

  def deleteExpired(before: Long): Task[Long] = {
    logged(run(ctx.run(quote(tokens.filter(_.expiresAt < lift(before)).delete)))) { rows =>
      s"passwordResetTokens.deleteExpired rows=$rows"
    }
  }
}
