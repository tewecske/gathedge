package webapp1.backend.db

import io.getquill.*
import zio.*

import javax.sql.DataSource

final class PostgresUserRepository(dataSource: DataSource) extends UserRepository {
  private val ctx = new PostgresZioJdbcContext(SnakeCase)
  import ctx._

  private inline def users = quote(querySchema[UserRow]("users"))

  private def run[T](q: zio.ZIO[DataSource, Throwable, T]): Task[T] = {
    q.provideEnvironment(ZEnvironment(dataSource))
  }

  def insert(
    email: String,
    passwordHash: Option[String],
    isAdmin: Boolean,
    googleSubject: Option[String],
    theme: String,
    createdAt: Long,
  ): Task[UserRow] = {
    val row = UserRow(0L, email, passwordHash, isAdmin, theme, googleSubject, createdAt)
    run(ctx.run(quote(users.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))
  }

  def findByEmail(email: String): Task[Option[UserRow]] = {
    run(ctx.run(quote(users.filter(_.email == lift(email))))).map(_.headOption)
  }

  def findById(id: Long): Task[Option[UserRow]] = {
    run(ctx.run(quote(users.filter(_.id == lift(id))))).map(_.headOption)
  }

  def findByGoogleSubject(googleSubject: String): Task[Option[UserRow]] = {
    run(ctx.run(quote(users.filter(_.googleSubject == lift(Option(googleSubject)))))).map(_.headOption)
  }

  def updateTheme(userId: Long, theme: String): Task[Unit] = {
    run(ctx.run(quote(users.filter(_.id == lift(userId)).update(_.theme -> lift(theme))))).unit
  }

  def existsAdmin: Task[Boolean] = {
    run(ctx.run(quote(users.filter(_.isAdmin).size))).map(_ > 0)
  }

  def listAll: Task[List[UserRow]] = {
    run(ctx.run(quote(users.sortBy(_.createdAt))))
  }

  def updateProfile(id: Long, email: String, isAdmin: Boolean): Task[Long] = {
    run(ctx.run(quote(users.filter(_.id == lift(id)).update(_.email -> lift(email), _.isAdmin -> lift(isAdmin)))))
  }

  def updatePasswordHash(id: Long, passwordHash: String): Task[Unit] = {
    run(ctx.run(quote(users.filter(_.id == lift(id)).update(_.passwordHash -> lift(Option(passwordHash)))))).unit
  }

  def deleteById(id: Long): Task[Long] = {
    run(ctx.run(quote(users.filter(_.id == lift(id)).delete)))
  }
}

object PostgresUserRepository {
  val live: ZLayer[DataSource, Nothing, UserRepository] =
    ZLayer.fromFunction((ds: DataSource) => new PostgresUserRepository(ds): UserRepository)
}

final class PostgresSessionRepository(dataSource: DataSource) extends SessionRepository {
  private val ctx = new PostgresZioJdbcContext(SnakeCase)
  import ctx._

  private inline def sessions = quote(querySchema[SessionRow]("sessions"))

  private def run[T](q: zio.ZIO[DataSource, Throwable, T]): Task[T] = {
    q.provideEnvironment(ZEnvironment(dataSource))
  }

  def insert(session: SessionRow): Task[Unit] = {
    run(ctx.run(quote(sessions.insertValue(lift(session))))).unit
  }

  def findActive(id: String, now: Long): Task[Option[SessionRow]] = {
    run(
      ctx.run(
        quote(
          sessions.filter(s => s.id == lift(id) && s.expiresAt > lift(now) && s.revokedAt.isEmpty)
        )
      )
    ).map(_.headOption)
  }

  def revoke(id: String, revokedAt: Long): Task[Unit] = {
    run(ctx.run(quote(sessions.filter(_.id == lift(id)).update(_.revokedAt -> lift(Option(revokedAt)))))).unit
  }
}

object PostgresSessionRepository {
  val live: ZLayer[DataSource, Nothing, SessionRepository] =
    ZLayer.fromFunction((ds: DataSource) => new PostgresSessionRepository(ds): SessionRepository)
}
