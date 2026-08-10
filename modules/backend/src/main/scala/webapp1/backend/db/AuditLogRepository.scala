package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import webapp1.shared.dto.AuditSort
import zio.*

import javax.sql.DataSource

/** Administrator actions, written by `AdminService.audit` alongside the `SecurityLog` line so the two cannot drift.
  *
  * Reads are paged by offset and counted, so the screen can number its pages and say how many entries there are. The
  * three filter arguments narrow both halves identically — see [[list]] and [[count]].
  */
trait AuditLogRepository {
  def insert(row: AuditLogRow): Task[AuditLogRow]

  /** One page, most recent first unless `sort` names a column out of `dto.AuditSort`. The three remaining arguments
    * narrow.
    */
  def list(
    offset: Int,
    limit: Int,
    sort: Option[String],
    descending: Boolean,
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): Task[List[AuditLogRow]]

  /** How many entries match the same three filters — the total [[list]]'s pages are counted off. */
  def count(action: Option[String], actorId: Option[Long], targetId: Option[String]): Task[Long]

  def countAll: Task[Long]
}

object AuditLogRepository {
  def insert(row: AuditLogRow): RIO[AuditLogRepository, AuditLogRow] =
    ZIO.serviceWithZIO[AuditLogRepository](_.insert(row))

  def list(
    offset: Int,
    limit: Int,
    sort: Option[String],
    descending: Boolean,
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): RIO[AuditLogRepository, List[AuditLogRow]] =
    ZIO.serviceWithZIO[AuditLogRepository](_.list(offset, limit, sort, descending, action, actorId, targetId))

  def count(
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): RIO[AuditLogRepository, Long] =
    ZIO.serviceWithZIO[AuditLogRepository](_.count(action, actorId, targetId))

  def countAll: RIO[AuditLogRepository, Long] =
    ZIO.serviceWithZIO[AuditLogRepository](_.countAll)

  val live: ZLayer[DataSource, Nothing, AuditLogRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new AuditLogRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): AuditLogRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, AuditLogRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new AuditLogRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): AuditLogRepository
  )
}

/** `actorEmail` and `detail` are readable prose, and `detail` may name an account — neither belongs in a log line, see
  * [[QuillRepository.logged]]. The messages carry ids, the action and row counts only.
  */
final class AuditLogRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with AuditLogRepository {
  import ctx._

  private inline def entries = quote(querySchema[AuditLogRow]("audit_log"))

  def insert(row: AuditLogRow): Task[AuditLogRow] = {
    val inserted = run(ctx.run(quote(entries.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id))) { entry =>
      s"auditLog.insert id=${entry.id} action=${row.action} actorUserId=${row.actorUserId}"
    }
  }

  /** The rows a page is cut from, before ordering.
    *
    * Quill's dynamic-query DSL rather than a `quote` block: three independent optional narrowings would otherwise be
    * eight query shapes, and `filterOpt` drops the clause entirely when the argument is `None` instead of emitting an
    * always-true predicate. Nothing is lost by it — the dialect is a type parameter here, so every query in this
    * package is already rendered at runtime rather than by the macro. [[list]] and [[count]] share it so the total
    * cannot count a different set than the page shows.
    */
  private def matching(
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): DynamicQuery[AuditLogRow] = {
    dynamicQuerySchema[AuditLogRow]("audit_log")
      .filterOpt(action)((entry, value) => quote(entry.action == unquote(value)))
      .filterOpt(actorId)((entry, id) => quote(entry.actorUserId.contains(unquote(id))))
      .filterOpt(targetId)((entry, id) => quote(entry.targetId.contains(unquote(id))))
  }

  /** The `dto.AuditSort` vocabulary translated to an `ORDER BY`; anything else is newest first, which is what an audit
    * trail is read in when nobody has said otherwise.
    */
  private def ordered(
    query: DynamicQuery[AuditLogRow],
    sort: Option[String],
    descending: Boolean,
  ): DynamicQuery[AuditLogRow] = {
    sort match {
      case Some(AuditSort.occurredAt) =>
        query.sortBy(_.occurredAt)(using ordering(descending))
      case Some(AuditSort.actor)      =>
        query.sortBy(_.actorEmail)(using ordering(descending))
      case Some(AuditSort.action)     =>
        query.sortBy(_.action)(using ordering(descending))
      case Some(AuditSort.ip)         =>
        query.sortBy(_.ip)(using ordering(descending))
      case _                          =>
        query.sortBy(_.occurredAt)(using Ord.desc)
    }
  }

  def list(
    offset: Int,
    limit: Int,
    sort: Option[String],
    descending: Boolean,
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): Task[List[AuditLogRow]] = {
    val page = ordered(matching(action, actorId, targetId), sort, descending).drop(offset).take(limit)
    logged(run(ctx.run(page))) { rows =>
      s"auditLog.list offset=$offset limit=$limit sort=${sort.getOrElse("-")} desc=$descending rows=${rows.size}"
    }
  }

  def count(action: Option[String], actorId: Option[Long], targetId: Option[String]): Task[Long] = {
    logged(run(ctx.run(matching(action, actorId, targetId).size)))(count => s"auditLog.count count=$count")
  }

  def countAll: Task[Long] = {
    logged(run(ctx.run(quote(entries.size))))(count => s"auditLog.countAll count=$count")
  }
}
