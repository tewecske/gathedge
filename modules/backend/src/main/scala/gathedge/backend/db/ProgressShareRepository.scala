package gathedge.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Progress-share codes and the (sharer, viewer) grants they mint — what lets one account read another's game history,
  * on either side's own say-so rather than a role like "parent" or "teacher".
  *
  * '''The code is the credential.''' Every method here is written so the column value never reaches a log line — the
  * rule [[QuillRepository.logged]] states for session ids applies to these unchanged, the same rule
  * [[GuestClaimCodeRepository]] follows for a guest's transfer code.
  */
trait ProgressShareRepository {

  def insertCode(userId: Long, code: String, createdAt: Long): Task[ProgressShareCodeRow]

  /** The live code, if this is one. A revoked row is not answered — see [[GuestClaimCodeRepository.findActive]]. */
  def findActiveCode(code: String): Task[Option[ProgressShareCodeRow]]

  /** This account's own live code, if it has minted one — what makes issuing a code idempotent, mirroring
    * [[GuestClaimCodeRepository.findActiveForUser]].
    */
  def findActiveCodeForUser(userId: Long): Task[Option[ProgressShareCodeRow]]

  def markCodeUsed(id: Long, usedAt: Long): Task[Unit]

  def insertShare(sharerUserId: Long, viewerUserId: Long, createdAt: Long): Task[ProgressShareRow]

  def findShare(sharerUserId: Long, viewerUserId: Long): Task[Option[ProgressShareRow]]

  /** Every account that may currently read `sharerUserId`'s game history. */
  def listViewersFor(sharerUserId: Long): Task[List[ProgressShareRow]]

  /** Every account whose game history `viewerUserId` may currently read. */
  def listSharersFor(viewerUserId: Long): Task[List[ProgressShareRow]]

  def deleteShare(sharerUserId: Long, viewerUserId: Long): Task[Long]
}

object ProgressShareRepository {

  def insertCode(userId: Long, code: String, createdAt: Long): RIO[ProgressShareRepository, ProgressShareCodeRow] =
    ZIO.serviceWithZIO[ProgressShareRepository](_.insertCode(userId, code, createdAt))

  def findActiveCode(code: String): RIO[ProgressShareRepository, Option[ProgressShareCodeRow]] =
    ZIO.serviceWithZIO[ProgressShareRepository](_.findActiveCode(code))

  def findActiveCodeForUser(userId: Long): RIO[ProgressShareRepository, Option[ProgressShareCodeRow]] =
    ZIO.serviceWithZIO[ProgressShareRepository](_.findActiveCodeForUser(userId))

  def markCodeUsed(id: Long, usedAt: Long): RIO[ProgressShareRepository, Unit] =
    ZIO.serviceWithZIO[ProgressShareRepository](_.markCodeUsed(id, usedAt))

  def insertShare(
    sharerUserId: Long,
    viewerUserId: Long,
    createdAt: Long,
  ): RIO[ProgressShareRepository, ProgressShareRow] =
    ZIO.serviceWithZIO[ProgressShareRepository](_.insertShare(sharerUserId, viewerUserId, createdAt))

  def findShare(sharerUserId: Long, viewerUserId: Long): RIO[ProgressShareRepository, Option[ProgressShareRow]] =
    ZIO.serviceWithZIO[ProgressShareRepository](_.findShare(sharerUserId, viewerUserId))

  def listViewersFor(sharerUserId: Long): RIO[ProgressShareRepository, List[ProgressShareRow]] =
    ZIO.serviceWithZIO[ProgressShareRepository](_.listViewersFor(sharerUserId))

  def listSharersFor(viewerUserId: Long): RIO[ProgressShareRepository, List[ProgressShareRow]] =
    ZIO.serviceWithZIO[ProgressShareRepository](_.listSharersFor(viewerUserId))

  def deleteShare(sharerUserId: Long, viewerUserId: Long): RIO[ProgressShareRepository, Long] =
    ZIO.serviceWithZIO[ProgressShareRepository](_.deleteShare(sharerUserId, viewerUserId))

  val live: ZLayer[DataSource, Nothing, ProgressShareRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new ProgressShareRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): ProgressShareRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, ProgressShareRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new ProgressShareRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): ProgressShareRepository
  )
}

final class ProgressShareRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with ProgressShareRepository {
  import ctx._

  private inline def codes  = quote(querySchema[ProgressShareCodeRow]("progress_share_codes"))
  private inline def shares = quote(querySchema[ProgressShareRow]("progress_shares"))

  def insertCode(userId: Long, code: String, createdAt: Long): Task[ProgressShareCodeRow] = {
    val row      = ProgressShareCodeRow(0L, userId, code, createdAt, None, None)
    val inserted = run(ctx.run(quote(codes.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id)))(saved => s"progressShareCodes.insert id=${saved.id} userId=$userId")
  }

  def findActiveCode(code: String): Task[Option[ProgressShareCodeRow]] = {
    val q = quote(codes.filter(row => row.code == lift(code) && row.revokedAt.isEmpty))
    logged(run(ctx.run(q)).map(_.headOption))(found => s"progressShareCodes.findActive found=${found.isDefined}")
  }

  def findActiveCodeForUser(userId: Long): Task[Option[ProgressShareCodeRow]] = {
    val q = quote(
      codes.filter(row => row.userId == lift(userId) && row.revokedAt.isEmpty).sortBy(_.createdAt)(using Ord.desc)
    )
    logged(run(ctx.run(q)).map(_.headOption)) { found =>
      s"progressShareCodes.findActiveForUser found=${found.isDefined}"
    }
  }

  def markCodeUsed(id: Long, usedAt: Long): Task[Unit] = {
    val q = quote(codes.filter(_.id == lift(id)).update(_.lastUsedAt -> lift(Option(usedAt))))
    logged(run(ctx.run(q)).unit)(_ => s"progressShareCodes.markUsed id=$id")
  }

  def insertShare(sharerUserId: Long, viewerUserId: Long, createdAt: Long): Task[ProgressShareRow] = {
    val row      = ProgressShareRow(0L, sharerUserId, viewerUserId, createdAt)
    val inserted = run(ctx.run(quote(shares.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id))) { saved =>
      s"progressShares.insert id=${saved.id} sharer=$sharerUserId viewer=$viewerUserId"
    }
  }

  def findShare(sharerUserId: Long, viewerUserId: Long): Task[Option[ProgressShareRow]] = {
    val q = quote {
      shares.filter(row => row.sharerUserId == lift(sharerUserId) && row.viewerUserId == lift(viewerUserId))
    }
    logged(run(ctx.run(q)).map(_.headOption))(found => s"progressShares.find found=${found.isDefined}")
  }

  def listViewersFor(sharerUserId: Long): Task[List[ProgressShareRow]] = {
    val q = quote(shares.filter(_.sharerUserId == lift(sharerUserId)).sortBy(_.createdAt)(using Ord.desc))
    logged(run(ctx.run(q)))(rows => s"progressShares.listViewersFor sharer=$sharerUserId rows=${rows.size}")
  }

  def listSharersFor(viewerUserId: Long): Task[List[ProgressShareRow]] = {
    val q = quote(shares.filter(_.viewerUserId == lift(viewerUserId)).sortBy(_.createdAt)(using Ord.desc))
    logged(run(ctx.run(q)))(rows => s"progressShares.listSharersFor viewer=$viewerUserId rows=${rows.size}")
  }

  def deleteShare(sharerUserId: Long, viewerUserId: Long): Task[Long] = {
    val q = quote {
      shares.filter(row => row.sharerUserId == lift(sharerUserId) && row.viewerUserId == lift(viewerUserId)).delete
    }
    logged(run(ctx.run(q)))(rows => s"progressShares.delete sharer=$sharerUserId viewer=$viewerUserId rows=$rows")
  }
}
