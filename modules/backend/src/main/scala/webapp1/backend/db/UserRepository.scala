package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Dialect-independent interface. [[PostgresUserRepository.live]] backs production (Postgres),
  * [[SqliteUserRepository.test]] backs tests (SQLite) — see the plan's "dual-dialect DB strategy". Both wrap the same
  * [[UserRepositoryLive]] below and are swapped in purely via ZLayer wiring.
  */
trait UserRepository {
  def insert(
    email: String,
    passwordHash: Option[String],
    isAdmin: Boolean,
    theme: String,
    createdAt: Long,
    emailVerifiedAt: Option[Long],
  ): Task[UserRow]
  def findByEmail(email: String): Task[Option[UserRow]]
  def findById(id: Long): Task[Option[UserRow]]
  def updateTheme(userId: Long, theme: String): Task[Unit]

  /** Idempotent: re-verifying an already-verified account just rewrites the timestamp. */
  def markEmailVerified(userId: Long, verifiedAt: Long): Task[Unit]
  def existsAdmin: Task[Boolean]
  def listAll: Task[List[UserRow]]

  /** Updates email/admin flag only if the row exists. Returns rows affected. */
  def updateProfile(id: Long, email: String, isAdmin: Boolean): Task[Long]
  def updatePasswordHash(id: Long, passwordHash: String): Task[Unit]

  /** Both updates as one unit of work — `passwordHash` of `None` leaves the password alone. Returns rows affected by
    * the profile update.
    */
  def updateProfileAndPassword(id: Long, email: String, isAdmin: Boolean, passwordHash: Option[String]): Task[Long]
  def deleteById(id: Long): Task[Long]
}

/** Dialect-generic implementation shared by both Postgres and SQLite. Quill's `ctx.run` dispatches SQL rendering off
  * `ctx.idiom` at runtime, so a single quoted-query body works for any `ZioJdbcContext[Dialect, Naming]` — no need to
  * hand-duplicate the query bodies per dialect, only the context instance differs (see the two `object`s below). Every
  * repository in this package is built the same way.
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
    emailVerifiedAt: Option[Long],
  ): Task[UserRow] = {
    val row = UserRow(0L, email, passwordHash, isAdmin, theme, createdAt, emailVerifiedAt)
    logged(run(ctx.run(quote(users.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))) {
      user =>
        s"users.insert id=${user.id} admin=$isAdmin verified=${emailVerifiedAt.isDefined}"
    }
  }

  def findByEmail(email: String): Task[Option[UserRow]] = {
    logged(run(ctx.run(quote(users.filter(_.email == lift(email))))).map(_.headOption)) { found =>
      s"users.findByEmail found=${found.isDefined}"
    }
  }

  def findById(id: Long): Task[Option[UserRow]] = {
    logged(run(ctx.run(quote(users.filter(_.id == lift(id))))).map(_.headOption)) { found =>
      s"users.findById id=$id found=${found.isDefined}"
    }
  }

  def updateTheme(userId: Long, theme: String): Task[Unit] = {
    logged(run(ctx.run(quote(users.filter(_.id == lift(userId)).update(_.theme -> lift(theme))))).unit) { _ =>
      s"users.updateTheme id=$userId theme=$theme"
    }
  }

  def markEmailVerified(userId: Long, verifiedAt: Long): Task[Unit] = {
    val q = quote(users.filter(_.id == lift(userId)).update(_.emailVerifiedAt -> lift(Option(verifiedAt))))
    logged(run(ctx.run(q)).unit)(_ => s"users.markEmailVerified id=$userId")
  }

  def existsAdmin: Task[Boolean] = {
    logged(run(ctx.run(quote(users.filter(_.isAdmin).size))).map(_ > 0)) { exists =>
      s"users.existsAdmin exists=$exists"
    }
  }

  def listAll: Task[List[UserRow]] = {
    logged(run(ctx.run(quote(users.sortBy(_.createdAt)))))(rows => s"users.listAll count=${rows.size}")
  }

  def updateProfile(id: Long, email: String, isAdmin: Boolean): Task[Long] = {
    val q = quote(users.filter(_.id == lift(id)).update(_.email -> lift(email), _.isAdmin -> lift(isAdmin)))
    logged(run(ctx.run(q)))(rows => s"users.updateProfile id=$id admin=$isAdmin rows=$rows")
  }

  def updatePasswordHash(id: Long, passwordHash: String): Task[Unit] = {
    val q = quote(users.filter(_.id == lift(id)).update(_.passwordHash -> lift(Option(passwordHash))))
    logged(run(ctx.run(q)).unit)(_ => s"users.updatePasswordHash id=$id")
  }

  def updateProfileAndPassword(id: Long, email: String, isAdmin: Boolean, passwordHash: Option[String]): Task[Long] = {
    val profile = ctx.run(
      quote(users.filter(_.id == lift(id)).update(_.email -> lift(email), _.isAdmin -> lift(isAdmin)))
    )
    val updated = {
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
    logged(updated) { rows =>
      s"users.updateProfileAndPassword id=$id admin=$isAdmin password=${passwordHash.isDefined} rows=$rows"
    }
  }

  def deleteById(id: Long): Task[Long] = {
    logged(run(ctx.run(quote(users.filter(_.id == lift(id)).delete))))(rows => s"users.deleteById id=$id rows=$rows")
  }
}

object PostgresUserRepository {
  val live: ZLayer[DataSource, Nothing, UserRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UserRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): UserRepository
  )
}

/** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
object SqliteUserRepository {
  val test: ZLayer[DataSource, Nothing, UserRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UserRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): UserRepository
  )
}
