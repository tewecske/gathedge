package gathedge.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Transfer codes: what carries a guest's vocabulary to a second machine, in place of a password.
  *
  * '''The code is the credential.''' Every method here is written so the column value never reaches a log line — the
  * rule [[QuillRepository.logged]] states for session ids applies to these unchanged.
  */
trait GuestClaimCodeRepository {

  def insert(userId: Long, code: String, createdAt: Long): Task[GuestClaimCodeRow]

  /** The live code, if this is one. A revoked row is not answered: revocation is what upgrading an account does, and a
    * code that outlived its guest must not sign anybody in.
    */
  def findActive(code: String): Task[Option[GuestClaimCodeRow]]

  def markUsed(id: Long, usedAt: Long): Task[Unit]

  /** Revokes every code an account has. Called when it stops being a guest. Returns rows affected. */
  def revokeAllFor(userId: Long, revokedAt: Long): Task[Long]

  def countFor(userId: Long): Task[Long]
}

object GuestClaimCodeRepository {

  def insert(userId: Long, code: String, createdAt: Long): RIO[GuestClaimCodeRepository, GuestClaimCodeRow] =
    ZIO.serviceWithZIO[GuestClaimCodeRepository](_.insert(userId, code, createdAt))

  def findActive(code: String): RIO[GuestClaimCodeRepository, Option[GuestClaimCodeRow]] =
    ZIO.serviceWithZIO[GuestClaimCodeRepository](_.findActive(code))

  def markUsed(id: Long, usedAt: Long): RIO[GuestClaimCodeRepository, Unit] =
    ZIO.serviceWithZIO[GuestClaimCodeRepository](_.markUsed(id, usedAt))

  def revokeAllFor(userId: Long, revokedAt: Long): RIO[GuestClaimCodeRepository, Long] =
    ZIO.serviceWithZIO[GuestClaimCodeRepository](_.revokeAllFor(userId, revokedAt))

  def countFor(userId: Long): RIO[GuestClaimCodeRepository, Long] =
    ZIO.serviceWithZIO[GuestClaimCodeRepository](_.countFor(userId))

  val live: ZLayer[DataSource, Nothing, GuestClaimCodeRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GuestClaimCodeRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GuestClaimCodeRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, GuestClaimCodeRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GuestClaimCodeRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GuestClaimCodeRepository
  )
}

final class GuestClaimCodeRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GuestClaimCodeRepository {
  import ctx._

  private inline def codes = quote(querySchema[GuestClaimCodeRow]("guest_claim_codes"))

  def insert(userId: Long, code: String, createdAt: Long): Task[GuestClaimCodeRow] = {
    val row      = GuestClaimCodeRow(0L, userId, code, createdAt, None, None)
    val inserted = run(ctx.run(quote(codes.insertValue(lift(row)).returningGenerated(_.id))))
    logged(inserted.map(id => row.copy(id = id)))(saved => s"guestClaimCodes.insert id=${saved.id} userId=$userId")
  }

  def findActive(code: String): Task[Option[GuestClaimCodeRow]] = {
    val q = quote(codes.filter(row => row.code == lift(code) && row.revokedAt.isEmpty))
    logged(run(ctx.run(q)).map(_.headOption))(found => s"guestClaimCodes.findActive found=${found.isDefined}")
  }

  def markUsed(id: Long, usedAt: Long): Task[Unit] = {
    val q = quote(codes.filter(_.id == lift(id)).update(_.lastUsedAt -> lift(Option(usedAt))))
    logged(run(ctx.run(q)).unit)(_ => s"guestClaimCodes.markUsed id=$id")
  }

  def revokeAllFor(userId: Long, revokedAt: Long): Task[Long] = {
    val q = quote(
      codes
        .filter(row => row.userId == lift(userId) && row.revokedAt.isEmpty)
        .update(_.revokedAt -> lift(Option(revokedAt)))
    )
    logged(run(ctx.run(q)))(rows => s"guestClaimCodes.revokeAll userId=$userId rows=$rows")
  }

  def countFor(userId: Long): Task[Long] = {
    val q = quote(codes.filter(row => row.userId == lift(userId) && row.revokedAt.isEmpty).size)
    logged(run(ctx.run(q)))(count => s"guestClaimCodes.countFor userId=$userId count=$count")
  }
}
