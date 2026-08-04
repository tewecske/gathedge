package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

final class GroupInvitationRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GroupInvitationRepository {
  import ctx._

  private inline def invitations = quote(querySchema[GroupInvitationRow]("group_invitations"))

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

final class OAuthIdentityRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with OAuthIdentityRepository {
  import ctx._

  private inline def identities = quote(querySchema[OAuthIdentityRow]("oauth_identities"))

  def findByProviderAndSubject(provider: String, subject: String): Task[Option[OAuthIdentityRow]] = {
    run(ctx.run(quote(identities.filter(i => i.provider == lift(provider) && i.subject == lift(subject))))).map(
      _.headOption
    )
  }

  def listForUser(userId: Long): Task[List[OAuthIdentityRow]] = {
    run(ctx.run(quote(identities.filter(_.userId == lift(userId)).sortBy(_.createdAt))))
  }

  def insert(row: OAuthIdentityRow): Task[OAuthIdentityRow] = {
    run(ctx.run(quote(identities.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))
  }

  def deleteByUserAndProvider(userId: Long, provider: String): Task[Long] = {
    run(ctx.run(quote(identities.filter(i => i.userId == lift(userId) && i.provider == lift(provider)).delete)))
  }
}

object PostgresOAuthIdentityRepository {
  val live: ZLayer[DataSource, Nothing, OAuthIdentityRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new OAuthIdentityRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): OAuthIdentityRepository
  )
}

object SqliteOAuthIdentityRepository {
  val live: ZLayer[DataSource, Nothing, OAuthIdentityRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new OAuthIdentityRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): OAuthIdentityRepository
  )
}
