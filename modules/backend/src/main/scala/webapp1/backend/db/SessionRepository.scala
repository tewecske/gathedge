package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

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

/** Session ids are bearer credentials, so no log line here carries one — see [[QuillRepository.logged]]. */
final class SessionRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with SessionRepository {
  import ctx._

  private inline def sessions = quote(querySchema[SessionRow]("sessions"))

  def insert(session: SessionRow): Task[Unit] = {
    logged(run(ctx.run(quote(sessions.insertValue(lift(session))))).unit) { _ =>
      s"sessions.insert userId=${session.userId} expiresAt=${session.expiresAt}"
    }
  }

  def findActive(id: String, now: Long): Task[Option[SessionRow]] = {
    val q = quote(sessions.filter(s => s.id == lift(id) && s.expiresAt > lift(now) && s.revokedAt.isEmpty))
    logged(run(ctx.run(q)).map(_.headOption)) { found =>
      s"sessions.findActive found=${found.isDefined}"
    }
  }

  def revoke(id: String, revokedAt: Long): Task[Unit] = {
    val q = quote(sessions.filter(_.id == lift(id)).update(_.revokedAt -> lift(Option(revokedAt))))
    logged(run(ctx.run(q)).unit)(_ => "sessions.revoke")
  }

  def revokeAllForUser(userId: Long, revokedAt: Long): Task[Unit] = {
    val q = quote {
      sessions
        .filter(s => s.userId == lift(userId) && s.revokedAt.isEmpty)
        .update(_.revokedAt -> lift(Option(revokedAt)))
    }
    logged(run(ctx.run(q)).unit)(_ => s"sessions.revokeAllForUser userId=$userId")
  }

  def deleteExpired(before: Long): Task[Long] = {
    val q = quote {
      sessions.filter(s => s.expiresAt < lift(before) || s.revokedAt.exists(_ < lift(before))).delete
    }
    logged(run(ctx.run(q)))(rows => s"sessions.deleteExpired rows=$rows")
  }
}

object PostgresSessionRepository {
  val live: ZLayer[DataSource, Nothing, SessionRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new SessionRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): SessionRepository
  )
}

/** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
object SqliteSessionRepository {
  val test: ZLayer[DataSource, Nothing, SessionRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new SessionRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): SessionRepository
  )
}
