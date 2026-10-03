package gathedge.backend.db

import io.getquill.*
import zio.*

import javax.sql.DataSource

/** The `user_achievements` table: one row per tier an account unlocked. */
trait AchievementRepository {

  /** Every tier the account unlocked, in no particular order. */
  def forUser(userId: Long): Task[List[UserAchievementRow]]

  /** Inserts each row that is not there yet, and answers the rows it did insert. A row already there (an earlier
    * evaluation, or one running at the same moment) is skipped, so a tier is never written twice.
    */
  def insertNew(rows: List[UserAchievementRow]): Task[List[UserAchievementRow]]
}

object AchievementRepository {
  def forUser(userId: Long): RIO[AchievementRepository, List[UserAchievementRow]] =
    ZIO.serviceWithZIO[AchievementRepository](_.forUser(userId))

  def insertNew(rows: List[UserAchievementRow]): RIO[AchievementRepository, List[UserAchievementRow]] =
    ZIO.serviceWithZIO[AchievementRepository](_.insertNew(rows))

  val live: ZLayer[DataSource, Nothing, AchievementRepository] =
    ZLayer.fromFunction((ds: DataSource) => new AchievementRepositoryLive(ds): AchievementRepository)
}

final class AchievementRepositoryLive(dataSource: DataSource)
    extends QuillRepository(dataSource, new PostgresZioJdbcContext(SnakeCase))
    with AchievementRepository {
  import ctx._

  private inline def achievements = quote(querySchema[UserAchievementRow]("user_achievements"))

  def forUser(userId: Long): Task[List[UserAchievementRow]] = {
    val q = quote(achievements.filter(row => row.userId == lift(userId)))
    logged(run(ctx.run(q)))(found => s"achievements.forUser userId=$userId rows=${found.size}")
  }

  def insertNew(rows: List[UserAchievementRow]): Task[List[UserAchievementRow]] = {
    // One statement per row, not a JDBC batch: the driver may rewrite a batch and then report no per-row count, and the
    // per-row count is what says which rows were new. An evaluation writes a handful of rows at most.
    val inserted = ZIO.foreach(rows) { row =>
      val q = quote(achievements.insertValue(lift(row)).onConflictIgnore)
      run(ctx.run(q)).map(count => Option.when(count > 0)(row))
    }
    logged(inserted.map(_.flatten)) { added =>
      s"achievements.insertNew userId=${rows.headOption.map(_.userId).getOrElse(0L)} tried=${rows.size} added=${added.size}"
    }
  }
}
