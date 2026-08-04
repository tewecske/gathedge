package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Dialect-generic implementation shared by both Postgres and SQLite. Quill's `ctx.run` dispatches SQL rendering off
  * `ctx.idiom` at runtime, so a single quoted-query body works for any `ZioJdbcContext[Dialect, Naming]` — no need to
  * hand-duplicate the query bodies per dialect, only the context instance differs (see the two `object`s below each
  * trait's implementation).
  */
final class UserRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with UserRepository {
  import ctx._

  private inline def users = quote(querySchema[UserRow]("users"))

  def insert(
    email: String,
    passwordHash: Option[String],
    isAdmin: Boolean,
    theme: String,
    createdAt: Long,
  ): Task[UserRow] = {
    val row = UserRow(0L, email, passwordHash, isAdmin, theme, createdAt)
    run(ctx.run(quote(users.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))
  }

  def findByEmail(email: String): Task[Option[UserRow]] = {
    run(ctx.run(quote(users.filter(_.email == lift(email))))).map(_.headOption)
  }

  def findById(id: Long): Task[Option[UserRow]] = {
    run(ctx.run(quote(users.filter(_.id == lift(id))))).map(_.headOption)
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

  def updateProfileAndPassword(id: Long, email: String, isAdmin: Boolean, passwordHash: Option[String]): Task[Long] = {
    val profile = ctx.run(
      quote(users.filter(_.id == lift(id)).update(_.email -> lift(email), _.isAdmin -> lift(isAdmin)))
    )
    passwordHash match {
      case None =>
        run(profile)
      case Some(hash) =>
        val password = ctx.run(quote(users.filter(_.id == lift(id)).update(_.passwordHash -> lift(Option(hash)))))
        // One unit of work: an admin edit must not be able to land the new password while leaving
        // the email/role change behind, or the other way round.
        transaction(password *> profile)
    }
  }

  def deleteById(id: Long): Task[Long] = {
    run(ctx.run(quote(users.filter(_.id == lift(id)).delete)))
  }
}

object PostgresUserRepository {
  val live: ZLayer[DataSource, Nothing, UserRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UserRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): UserRepository
  )
}

object SqliteUserRepository {
  val live: ZLayer[DataSource, Nothing, UserRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UserRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): UserRepository
  )
}

final class SessionRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with SessionRepository {
  import ctx._

  private inline def sessions = quote(querySchema[SessionRow]("sessions"))

  def insert(session: SessionRow): Task[Unit] = {
    run(ctx.run(quote(sessions.insertValue(lift(session))))).unit
  }

  def findActive(id: String, now: Long): Task[Option[SessionRow]] = {
    run(ctx.run(quote(sessions.filter(s => s.id == lift(id) && s.expiresAt > lift(now) && s.revokedAt.isEmpty)))).map(
      _.headOption
    )
  }

  def revoke(id: String, revokedAt: Long): Task[Unit] = {
    run(ctx.run(quote(sessions.filter(_.id == lift(id)).update(_.revokedAt -> lift(Option(revokedAt)))))).unit
  }

  def revokeAllForUser(userId: Long, revokedAt: Long): Task[Unit] = {
    val q = quote {
      sessions
        .filter(s => s.userId == lift(userId) && s.revokedAt.isEmpty)
        .update(_.revokedAt -> lift(Option(revokedAt)))
    }
    run(ctx.run(q)).unit
  }

  def deleteExpired(before: Long): Task[Long] = {
    val q = quote {
      sessions.filter(s => s.expiresAt < lift(before) || s.revokedAt.exists(_ < lift(before))).delete
    }
    run(ctx.run(q))
  }
}

object PostgresSessionRepository {
  val live: ZLayer[DataSource, Nothing, SessionRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new SessionRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): SessionRepository
  )
}

object SqliteSessionRepository {
  val live: ZLayer[DataSource, Nothing, SessionRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new SessionRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): SessionRepository
  )
}
