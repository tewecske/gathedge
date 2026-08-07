package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Persistent sign-in history. Same dual-dialect shape as every other repository here: one generic implementation, two
  * thin `ZLayer`s on the companion.
  *
  * Reads are administrator-facing (`AdminService.userDetail`, `AdminService.loginAttempts`); the write is on the login
  * path and must never be able to fail it, which is the caller's job, not this one's.
  */
trait LoginAttemptRepository {
  def insert(row: LoginAttemptRow): Task[LoginAttemptRow]

  /** Most recent first. */
  def recentForUser(userId: Long, limit: Int): Task[List[LoginAttemptRow]]

  /** Most recent first, by typed address — catches the attempts made before an account existed, and the ones made
    * against an address that never had one.
    */
  def recentForEmail(email: String, limit: Int): Task[List[LoginAttemptRow]]

  /** Most recent first across every address, optionally narrowed to one outcome. */
  def recent(limit: Int, outcome: Option[String]): Task[List[LoginAttemptRow]]

  /** The origins an address was recently attempted from, so clearing a lockout can clear the IP dimension of it too —
    * `AuthService` blocks if *either* key trips, and clearing only the email key would leave the user locked.
    */
  def distinctRecentIps(email: String, since: Long): Task[List[String]]

  def countSince(since: Long, outcome: Option[String]): Task[Long]
  def countAll: Task[Long]
  def deleteOlderThan(before: Long): Task[Long]
}

object LoginAttemptRepository {
  def insert(row: LoginAttemptRow): RIO[LoginAttemptRepository, LoginAttemptRow] =
    ZIO.serviceWithZIO[LoginAttemptRepository](_.insert(row))

  def recentForUser(userId: Long, limit: Int): RIO[LoginAttemptRepository, List[LoginAttemptRow]] =
    ZIO.serviceWithZIO[LoginAttemptRepository](_.recentForUser(userId, limit))

  def recentForEmail(email: String, limit: Int): RIO[LoginAttemptRepository, List[LoginAttemptRow]] =
    ZIO.serviceWithZIO[LoginAttemptRepository](_.recentForEmail(email, limit))

  def recent(limit: Int, outcome: Option[String]): RIO[LoginAttemptRepository, List[LoginAttemptRow]] =
    ZIO.serviceWithZIO[LoginAttemptRepository](_.recent(limit, outcome))

  def distinctRecentIps(email: String, since: Long): RIO[LoginAttemptRepository, List[String]] =
    ZIO.serviceWithZIO[LoginAttemptRepository](_.distinctRecentIps(email, since))

  def countSince(since: Long, outcome: Option[String]): RIO[LoginAttemptRepository, Long] =
    ZIO.serviceWithZIO[LoginAttemptRepository](_.countSince(since, outcome))

  def countAll: RIO[LoginAttemptRepository, Long] =
    ZIO.serviceWithZIO[LoginAttemptRepository](_.countAll)

  def deleteOlderThan(before: Long): RIO[LoginAttemptRepository, Long] =
    ZIO.serviceWithZIO[LoginAttemptRepository](_.deleteOlderThan(before))

  val live: ZLayer[DataSource, Nothing, LoginAttemptRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new LoginAttemptRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): LoginAttemptRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, LoginAttemptRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new LoginAttemptRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): LoginAttemptRepository
  )
}

/** Every row here is *about* an email address and an origin, and neither may appear in a log line — see
  * [[QuillRepository.logged]]. The messages carry the surrogate id, the outcome and row counts only.
  */
final class LoginAttemptRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with LoginAttemptRepository {
  import ctx._

  private inline def attempts = quote(querySchema[LoginAttemptRow]("login_attempts"))

  def insert(row: LoginAttemptRow): Task[LoginAttemptRow] = {
    val inserted = run(ctx.run(quote(attempts.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id))) { attempt =>
      s"loginAttempts.insert id=${attempt.id} userId=${row.userId} outcome=${row.outcome}"
    }
  }

  def recentForUser(userId: Long, limit: Int): Task[List[LoginAttemptRow]] = {
    val q = quote {
      attempts.filter(_.userId.contains(lift(userId))).sortBy(_.createdAt)(using Ord.desc).take(lift(limit))
    }
    logged(run(ctx.run(q))) { rows =>
      s"loginAttempts.recentForUser userId=$userId rows=${rows.size}"
    }
  }

  def recentForEmail(email: String, limit: Int): Task[List[LoginAttemptRow]] = {
    val q = quote {
      attempts.filter(_.email == lift(email)).sortBy(_.createdAt)(using Ord.desc).take(lift(limit))
    }
    logged(run(ctx.run(q)))(rows => s"loginAttempts.recentForEmail rows=${rows.size}")
  }

  def recent(limit: Int, outcome: Option[String]): Task[List[LoginAttemptRow]] = {
    // `filterOpt` drops the clause when the argument is `None`, rather than emitting an always-true predicate. See
    // the note in `AuditLogRepository.list`.
    val q = {
      dynamicQuerySchema[LoginAttemptRow]("login_attempts")
        .filterOpt(outcome)((attempt, value) => quote(attempt.outcome == unquote(value)))
        .sortBy(_.createdAt)(using Ord.desc)
        .take(limit)
    }
    logged(run(ctx.run(q)))(rows => s"loginAttempts.recent rows=${rows.size}")
  }

  def distinctRecentIps(email: String, since: Long): Task[List[String]] = {
    val q = quote {
      attempts.filter(a => a.email == lift(email) && a.createdAt >= lift(since)).map(_.ip)
    }
    logged(run(ctx.run(q)).map(_.flatten.distinct)) { ips =>
      s"loginAttempts.distinctRecentIps count=${ips.size}"
    }
  }

  def countSince(since: Long, outcome: Option[String]): Task[Long] = {
    // Counted in SQL, not by loading the rows: this one feeds the system page's "failed sign-ins in the last 24 hours"
    // tile, and the table it counts is the fastest-growing one in the schema. Two quotes, because there are only two
    // shapes and `filterOpt` would move the count client-side.
    val q = outcome match {
      case Some(value) =>
        quote(attempts.filter(a => a.createdAt >= lift(since) && a.outcome == lift(value)).size)
      case None        =>
        quote(attempts.filter(_.createdAt >= lift(since)).size)
    }
    logged(run(ctx.run(q)))(count => s"loginAttempts.countSince count=$count")
  }

  def countAll: Task[Long] = {
    logged(run(ctx.run(quote(attempts.size))))(count => s"loginAttempts.countAll count=$count")
  }

  def deleteOlderThan(before: Long): Task[Long] = {
    logged(run(ctx.run(quote(attempts.filter(_.createdAt < lift(before)).delete)))) { rows =>
      s"loginAttempts.deleteOlderThan rows=$rows"
    }
  }
}
