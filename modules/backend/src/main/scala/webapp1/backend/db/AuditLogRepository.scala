package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Administrator actions, written by `AdminService.audit` alongside the `SecurityLog` line so the two cannot drift.
  *
  * Reads page backwards through `occurredAt`: `before` is the oldest `occurredAt` already shown, which is why the list
  * is ordered by it descending and why the filters narrow rather than reorder.
  */
trait AuditLogRepository {
  def insert(row: AuditLogRow): Task[AuditLogRow]

  /** Most recent first. `before` pages backwards (strictly older than the given `occurredAt`); the three remaining
    * arguments narrow.
    */
  def list(
    limit: Int,
    before: Option[Long],
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): Task[List[AuditLogRow]]

  def countAll: Task[Long]
}

object AuditLogRepository {
  def insert(row: AuditLogRow): RIO[AuditLogRepository, AuditLogRow] =
    ZIO.serviceWithZIO[AuditLogRepository](_.insert(row))

  def list(
    limit: Int,
    before: Option[Long],
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): RIO[AuditLogRepository, List[AuditLogRow]] =
    ZIO.serviceWithZIO[AuditLogRepository](_.list(limit, before, action, actorId, targetId))

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

  def list(
    limit: Int,
    before: Option[Long],
    action: Option[String],
    actorId: Option[Long],
    targetId: Option[String],
  ): Task[List[AuditLogRow]] = {
    // Quill's dynamic-query DSL rather than a `quote` block: four independent optional narrowings would otherwise be
    // sixteen query shapes, and `filterOpt` drops the clause entirely when the argument is `None` instead of emitting
    // an always-true predicate. Nothing is lost by it — the dialect is a type parameter here, so every query in this
    // package is already rendered at runtime rather than by the macro.
    val q = {
      dynamicQuerySchema[AuditLogRow]("audit_log")
        .filterOpt(before)((entry, cutoff) => quote(entry.occurredAt < cutoff))
        .filterOpt(action)((entry, value) => quote(entry.action == unquote(value)))
        .filterOpt(actorId)((entry, id) => quote(entry.actorUserId.contains(unquote(id))))
        .filterOpt(targetId)((entry, id) => quote(entry.targetId.contains(unquote(id))))
        .sortBy(_.occurredAt)(using Ord.desc)
        .take(limit)
    }
    logged(run(ctx.run(q)))(rows => s"auditLog.list rows=${rows.size}")
  }

  def countAll: Task[Long] = {
    logged(run(ctx.run(quote(entries.size))))(count => s"auditLog.countAll count=$count")
  }
}
