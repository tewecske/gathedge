package gathedge.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** One row per API request. Same dual-dialect shape as every other repository here: one generic implementation, two
  * thin `ZLayer`s on the companion.
  *
  * The write is on `RouteSupport.usageTracking`, once per request, and must never be able to fail it — that is the
  * caller's job, not this one's, exactly as `LoginAttemptRepository.insert` is. The reads are administrator-facing
  * (`UsageStatsService`).
  */
trait UsageEventRepository {
  def insert(row: UsageEventRow): Task[UsageEventRow]

  /** How many requests each (method, route) pair received since `since` — a GET and a DELETE on the same path are
    * different features. Most-used first is the caller's job; this answers every pair that had at least one.
    */
  def countsByRoute(since: Long): Task[List[(String, String, Long)]]

  /** How many requests each user made since `since`. `userId` non-null rows only: an anonymous caller has no account to
    * flag.
    */
  def countsByUser(since: Long): Task[List[(Long, Long)]]

  /** How many distinct origins each user was seen from since `since`. Deduplicated in Scala rather than with a SQL
    * `DISTINCT`, the same idiom `LoginAttemptRepository.distinctRecentIps` uses for the same shape of question.
    */
  def distinctIpCountsByUser(since: Long): Task[List[(Long, Int)]]

  def countAll: Task[Long]
  def deleteOlderThan(before: Long): Task[Long]
}

object UsageEventRepository {
  def insert(row: UsageEventRow): RIO[UsageEventRepository, UsageEventRow] =
    ZIO.serviceWithZIO[UsageEventRepository](_.insert(row))

  def countsByRoute(since: Long): RIO[UsageEventRepository, List[(String, String, Long)]] =
    ZIO.serviceWithZIO[UsageEventRepository](_.countsByRoute(since))

  def countsByUser(since: Long): RIO[UsageEventRepository, List[(Long, Long)]] =
    ZIO.serviceWithZIO[UsageEventRepository](_.countsByUser(since))

  def distinctIpCountsByUser(since: Long): RIO[UsageEventRepository, List[(Long, Int)]] =
    ZIO.serviceWithZIO[UsageEventRepository](_.distinctIpCountsByUser(since))

  def countAll: RIO[UsageEventRepository, Long] =
    ZIO.serviceWithZIO[UsageEventRepository](_.countAll)

  def deleteOlderThan(before: Long): RIO[UsageEventRepository, Long] =
    ZIO.serviceWithZIO[UsageEventRepository](_.deleteOlderThan(before))

  val live: ZLayer[DataSource, Nothing, UsageEventRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UsageEventRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): UsageEventRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, UsageEventRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new UsageEventRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): UsageEventRepository
  )
}

/** Every row here is *about* a request; the message carries the surrogate id, the route and a row count only — never
  * the ip, per [[QuillRepository.logged]].
  */
final class UsageEventRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with UsageEventRepository {
  import ctx._

  private inline def events = quote(querySchema[UsageEventRow]("usage_events"))

  def insert(row: UsageEventRow): Task[UsageEventRow] = {
    val inserted = run(ctx.run(quote(events.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id))) { event =>
      s"usageEvents.insert id=${event.id} route=${row.route}"
    }
  }

  def countsByRoute(since: Long): Task[List[(String, String, Long)]] = {
    val q = quote {
      events
        .filter(_.createdAt >= lift(since))
        .groupBy(row => (row.method, row.route))
        .map { case ((method, route), rows) => (method, route, rows.size) }
    }
    logged(run(ctx.run(q)))(rows => s"usageEvents.countsByRoute routes=${rows.size}")
  }

  def countsByUser(since: Long): Task[List[(Long, Long)]] = {
    val q = quote {
      events
        .filter(row => row.createdAt >= lift(since) && row.userId.isDefined)
        .groupBy(_.userId)
        .map { case (userId, rows) => (userId, rows.size) }
    }
    logged(run(ctx.run(q)).map(_.collect { case (Some(userId), count) => (userId, count) })) { rows =>
      s"usageEvents.countsByUser users=${rows.size}"
    }
  }

  def distinctIpCountsByUser(since: Long): Task[List[(Long, Int)]] = {
    val q       = quote {
      events.filter(row => row.createdAt >= lift(since) && row.userId.isDefined).map(row => (row.userId, row.ip))
    }
    val counted = run(ctx.run(q)).map { pairs =>
      pairs
        .collect { case (Some(userId), Some(ip)) => (userId, ip) }
        .distinct
        .groupBy(_._1)
        .view
        .mapValues(_.size)
        .toList
    }
    logged(counted)(rows => s"usageEvents.distinctIpCountsByUser users=${rows.size}")
  }

  def countAll: Task[Long] = {
    logged(run(ctx.run(quote(events.size))))(count => s"usageEvents.countAll count=$count")
  }

  def deleteOlderThan(before: Long): Task[Long] = {
    logged(run(ctx.run(quote(events.filter(_.createdAt < lift(before)).delete)))) { rows =>
      s"usageEvents.deleteOlderThan rows=$rows"
    }
  }
}
