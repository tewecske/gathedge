package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** One row of Flyway's own history table, as the system overview reports it.
  *
  * `installedOn` is deliberately absent: Flyway declares it as a native `TIMESTAMP`, the one type this schema otherwise
  * avoids precisely because the two dialects disagree about it (hence the epoch-millis convention every migration
  * follows). Version, description and order are enough to answer "is this database at the schema this build expects".
  */
final case class MigrationRow(installedRank: Int, version: Option[String], description: String, success: Boolean)

/** Row counts across the whole schema, for the administrator's system overview.
  *
  * Deliberately counts only. The tables it reaches into include `todo_items` and `group_pairs`, whose *contents* no
  * administrator may read (see summary.md) — a count says how much the deployment is holding without saying anything
  * about what any user wrote.
  */
final case class TableCounts(
  users: Long,
  admins: Long,
  unverifiedUsers: Long,
  usersWithoutPassword: Long,
  oauthIdentities: Long,
  todoItems: Long,
  groups: Long,
  groupMembers: Long,
  groupPairs: Long,
  invitations: Long,
  pendingInvitations: Long,
  acceptedInvitations: Long,
)

/** The one repository that is not backed by a table of its own: it answers the aggregate questions the system overview
  * asks, across tables owned by the other repositories.
  *
  * It exists so `SystemService` needs one dependency rather than nine, and so the counts are written once instead of
  * being scattered as a `countAll` on every repository that happens to need one.
  */
trait MetricsRepository {
  def counts(now: Long): Task[TableCounts]

  /** Flyway's applied-migration history, in the order it applied them. */
  def migrations: Task[List[MigrationRow]]
}

object MetricsRepository {
  def counts(now: Long): RIO[MetricsRepository, TableCounts] =
    ZIO.serviceWithZIO[MetricsRepository](_.counts(now))

  def migrations: RIO[MetricsRepository, List[MigrationRow]] =
    ZIO.serviceWithZIO[MetricsRepository](_.migrations)

  val live: ZLayer[DataSource, Nothing, MetricsRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new MetricsRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): MetricsRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, MetricsRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new MetricsRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): MetricsRepository
  )
}

final class MetricsRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with MetricsRepository {
  import ctx._

  private inline def users       = quote(querySchema[UserRow]("users"))
  private inline def identities  = quote(querySchema[OAuthIdentityRow]("oauth_identities"))
  private inline def todos       = quote(querySchema[TodoItemRow]("todo_items"))
  private inline def groups      = quote(querySchema[GroupRow]("groups"))
  private inline def members     = quote(querySchema[GroupMemberRow]("group_members"))
  private inline def pairs       = quote(querySchema[GroupPairRow]("group_pairs"))
  private inline def invitations = quote(querySchema[GroupInvitationRow]("group_invitations"))
  private inline def flywayRows  = quote(querySchema[MigrationRow]("flyway_schema_history"))

  def counts(now: Long): Task[TableCounts] = {
    // Twelve round trips rather than one twelve-column SELECT: Quill has no cross-table aggregate combinator, and
    // hand-writing the union as `infix` would have to be written twice, once per dialect. This runs once per load of
    // an administrator-only page.
    // The bindings are all suffixed `Count` so none of them shadows the `inline def` of the same name — a
    // for-comprehension binding is in scope for every later generator, and `users <- …` would quietly turn the next
    // `quote(users.filter(…))` into a filter over a Long.
    val pendingQuery = quote(invitations.filter(i => i.acceptedAt.isEmpty && i.expiresAt > lift(now)).size)

    val counted = {
      for {
        userCount            <- run(ctx.run(quote(users.size)))
        adminCount           <- run(ctx.run(quote(users.filter(_.isAdmin).size)))
        unverifiedCount      <- run(ctx.run(quote(users.filter(_.emailVerifiedAt.isEmpty).size)))
        withoutPasswordCount <- run(ctx.run(quote(users.filter(_.passwordHash.isEmpty).size)))
        identityCount        <- run(ctx.run(quote(identities.size)))
        todoCount            <- run(ctx.run(quote(todos.size)))
        groupCount           <- run(ctx.run(quote(groups.size)))
        memberCount          <- run(ctx.run(quote(members.size)))
        pairCount            <- run(ctx.run(quote(pairs.size)))
        invitationCount      <- run(ctx.run(quote(invitations.size)))
        pendingCount         <- run(ctx.run(pendingQuery))
        acceptedCount        <- run(ctx.run(quote(invitations.filter(_.acceptedAt.isDefined).size)))
      } yield TableCounts(
        users = userCount,
        admins = adminCount,
        unverifiedUsers = unverifiedCount,
        usersWithoutPassword = withoutPasswordCount,
        oauthIdentities = identityCount,
        todoItems = todoCount,
        groups = groupCount,
        groupMembers = memberCount,
        groupPairs = pairCount,
        invitations = invitationCount,
        pendingInvitations = pendingCount,
        acceptedInvitations = acceptedCount,
      )
    }
    logged(counted)(result => s"metrics.counts users=${result.users} groups=${result.groups}")
  }

  def migrations: Task[List[MigrationRow]] = {
    logged(run(ctx.run(quote(flywayRows.sortBy(_.installedRank))))) { rows =>
      s"metrics.migrations rows=${rows.size}"
    }
  }
}
