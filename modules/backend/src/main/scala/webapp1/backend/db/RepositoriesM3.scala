package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

final class GroupInvitationRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  ctx: ZioJdbcContext[Dialect, Naming],
) extends GroupInvitationRepository {
  import ctx._

  private inline def invitations = quote(querySchema[GroupInvitationRow]("group_invitations"))

  private def run[T](q: zio.ZIO[DataSource, Throwable, T]): Task[T] = q.provideEnvironment(ZEnvironment(dataSource))

  def insert(row: GroupInvitationRow): Task[GroupInvitationRow] = {
    run(ctx.run(quote(invitations.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))
  }

  def findByToken(token: String): Task[Option[GroupInvitationRow]] = {
    run(ctx.run(quote(invitations.filter(_.token == lift(token))))).map(_.headOption)
  }

  def markAccepted(token: String, acceptedAt: Long): Task[Unit] = {
    run(ctx.run(quote(invitations.filter(_.token == lift(token)).update(_.acceptedAt -> lift(Option(acceptedAt))))))
      .unit
  }
}

object PostgresGroupInvitationRepository {
  val live: ZLayer[DataSource, Nothing, GroupInvitationRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupInvitationRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GroupInvitationRepository
  )
}

object SqliteGroupInvitationRepository {
  val live: ZLayer[DataSource, Nothing, GroupInvitationRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupInvitationRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GroupInvitationRepository
  )
}
