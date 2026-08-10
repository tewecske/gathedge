package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Dialect-independent interface. [[UserRepository.live]] backs production (Postgres), [[UserRepository.test]] backs
  * tests (SQLite) — see the plan's "dual-dialect DB strategy". Both wrap the same [[UserRepositoryLive]] below and are
  * swapped in purely via ZLayer wiring.
  */
trait UserRepository {
  def insert(
    email: String,
    passwordHash: Option[String],
    isAdmin: Boolean,
    theme: String,
    locale: String,
    createdAt: Long,
    emailVerifiedAt: Option[Long],
  ): Task[UserRow]
  def findByEmail(email: String): Task[Option[UserRow]]
  def findById(id: Long): Task[Option[UserRow]]
  def updateTheme(userId: Long, theme: String): Task[Unit]
  def updateLocale(userId: Long, locale: String): Task[Unit]

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

/** Accessors, so a caller writes `UserRepository.findById(id)` instead of pulling the repository out of the environment
  * first. Every repository in this package has one, alongside its two `ZLayer`s — `live` (Postgres) and `test`
  * (SQLite).
  */
object UserRepository {
  def insert(
    email: String,
    passwordHash: Option[String],
    isAdmin: Boolean,
    theme: String,
    locale: String,
    createdAt: Long,
    emailVerifiedAt: Option[Long],
  ): RIO[UserRepository, UserRow] = {
    ZIO.serviceWithZIO[UserRepository](
      _.insert(email, passwordHash, isAdmin, theme, locale, createdAt, emailVerifiedAt)
    )
  }

  def findByEmail(email: String): RIO[UserRepository, Option[UserRow]] =
    ZIO.serviceWithZIO[UserRepository](_.findByEmail(email))

  def findById(id: Long): RIO[UserRepository, Option[UserRow]] =
    ZIO.serviceWithZIO[UserRepository](_.findById(id))

  def updateTheme(userId: Long, theme: String): RIO[UserRepository, Unit] =
    ZIO.serviceWithZIO[UserRepository](_.updateTheme(userId, theme))

  def updateLocale(userId: Long, locale: String): RIO[UserRepository, Unit] =
    ZIO.serviceWithZIO[UserRepository](_.updateLocale(userId, locale))

  def markEmailVerified(userId: Long, verifiedAt: Long): RIO[UserRepository, Unit] =
    ZIO.serviceWithZIO[UserRepository](_.markEmailVerified(userId, verifiedAt))

  def existsAdmin: RIO[UserRepository, Boolean] =
    ZIO.serviceWithZIO[UserRepository](_.existsAdmin)

  def listAll: RIO[UserRepository, List[UserRow]] =
    ZIO.serviceWithZIO[UserRepository](_.listAll)

  def updateProfile(id: Long, email: String, isAdmin: Boolean): RIO[UserRepository, Long] =
    ZIO.serviceWithZIO[UserRepository](_.updateProfile(id, email, isAdmin))

  def updatePasswordHash(id: Long, passwordHash: String): RIO[UserRepository, Unit] =
    ZIO.serviceWithZIO[UserRepository](_.updatePasswordHash(id, passwordHash))

  def updateProfileAndPassword(
    id: Long,
    email: String,
    isAdmin: Boolean,
    passwordHash: Option[String],
  ): RIO[UserRepository, Long] =
    ZIO.serviceWithZIO[UserRepository](_.updateProfileAndPassword(id, email, isAdmin, passwordHash))

  def deleteById(id: Long): RIO[UserRepository, Long] =
    ZIO.serviceWithZIO[UserRepository](_.deleteById(id))

  val live: ZLayer[DataSource, Nothing, UserRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UserRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): UserRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, UserRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UserRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): UserRepository
  )
}

/** Dialect-generic implementation shared by both Postgres and SQLite. Quill's `ctx.run` dispatches SQL rendering off
  * `ctx.idiom` at runtime, so a single quoted-query body works for any `ZioJdbcContext[Dialect, Naming]` — no need to
  * hand-duplicate the query bodies per dialect, only the context instance differs (see `live`/`test` above). Every
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
    locale: String,
    createdAt: Long,
    emailVerifiedAt: Option[Long],
  ): Task[UserRow] = {
    val row = UserRow(0L, email, passwordHash, isAdmin, theme, locale, createdAt, emailVerifiedAt)
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

  def updateLocale(userId: Long, locale: String): Task[Unit] = {
    logged(run(ctx.run(quote(users.filter(_.id == lift(userId)).update(_.locale -> lift(locale))))).unit) { _ =>
      s"users.updateLocale id=$userId locale=$locale"
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
        case None       =>
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
