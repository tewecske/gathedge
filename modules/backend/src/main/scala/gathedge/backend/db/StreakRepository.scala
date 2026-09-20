package gathedge.backend.db

import io.getquill.*
import zio.*

import javax.sql.DataSource

/** The `user_streaks` table: at most one row per account. */
trait StreakRepository {
  def find(userId: Long): Task[Option[UserStreakRow]]

  /** Inserts the row, or replaces the account's existing one. */
  def upsert(row: UserStreakRow): Task[Unit]
}

object StreakRepository {
  def find(userId: Long): RIO[StreakRepository, Option[UserStreakRow]] =
    ZIO.serviceWithZIO[StreakRepository](_.find(userId))

  def upsert(row: UserStreakRow): RIO[StreakRepository, Unit] =
    ZIO.serviceWithZIO[StreakRepository](_.upsert(row))

  val live: ZLayer[DataSource, Nothing, StreakRepository] =
    ZLayer.fromFunction((ds: DataSource) => new StreakRepositoryLive(ds): StreakRepository)
}

final class StreakRepositoryLive(dataSource: DataSource)
    extends QuillRepository(dataSource, new PostgresZioJdbcContext(SnakeCase))
    with StreakRepository {
  import ctx._

  private inline def streaks = quote(querySchema[UserStreakRow]("user_streaks"))

  def find(userId: Long): Task[Option[UserStreakRow]] = {
    val q = quote(streaks.filter(row => row.userId == lift(userId)))
    logged(run(ctx.run(q)).map(_.headOption))(found => s"streaks.find userId=$userId found=${found.isDefined}")
  }

  def upsert(row: UserStreakRow): Task[Unit] = {
    val q = quote {
      streaks
        .insertValue(lift(row))
        .onConflictUpdate(_.userId)(
          (existing, excluded) => existing.currentStreak -> excluded.currentStreak,
          (existing, excluded) => existing.longestStreak -> excluded.longestStreak,
          (existing, excluded) => existing.totalDays     -> excluded.totalDays,
          (existing, excluded) => existing.lastPlayDay   -> excluded.lastPlayDay,
          (existing, excluded) => existing.updatedAt     -> excluded.updatedAt,
        )
    }
    logged(run(ctx.run(q)).unit)(_ => s"streaks.upsert userId=${row.userId} current=${row.currentStreak}")
  }
}
