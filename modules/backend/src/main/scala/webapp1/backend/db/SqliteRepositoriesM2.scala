package webapp1.backend.db

import io.getquill.*
import zio.*

import javax.sql.DataSource

final class SqliteTodoRepository(dataSource: DataSource) extends TodoRepository {
  private val ctx = new SqliteZioJdbcContext(SnakeCase)
  import ctx._

  private inline def todos = quote(querySchema[TodoItemRow]("todo_items"))

  private def run[T](q: zio.ZIO[DataSource, Throwable, T]): Task[T] = q.provideEnvironment(ZEnvironment(dataSource))

  def insert(userId: Long, text: String, status: String, createdAt: Long): Task[TodoItemRow] = {
    val row = TodoItemRow(0L, userId, text, status, createdAt)
    run(ctx.run(quote(todos.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))
  }

  def listForUser(userId: Long): Task[List[TodoItemRow]] = {
    run(ctx.run(quote(todos.filter(_.userId == lift(userId)).sortBy(_.createdAt))))
  }

  def updateStatus(id: Long, userId: Long, status: String): Task[Option[TodoItemRow]] = {
    for {
      affected <- run(
                    ctx.run(quote(todos.filter(t => t.id == lift(id) && t.userId == lift(userId)).update(_.status -> lift(status))))
                  )
      result <- if (affected > 0) run(ctx.run(quote(todos.filter(_.id == lift(id))))).map(_.headOption) else ZIO.none
    } yield result
  }
}

object SqliteTodoRepository {
  val live: ZLayer[DataSource, Nothing, TodoRepository] =
    ZLayer.fromFunction((ds: DataSource) => new SqliteTodoRepository(ds): TodoRepository)
}

final class SqliteGroupRepository(dataSource: DataSource) extends GroupRepository {
  private val ctx = new SqliteZioJdbcContext(SnakeCase)
  import ctx._

  private inline def groupsQ  = quote(querySchema[GroupRow]("groups"))
  private inline def membersQ = quote(querySchema[GroupMemberRow]("group_members"))

  private def run[T](q: zio.ZIO[DataSource, Throwable, T]): Task[T] = q.provideEnvironment(ZEnvironment(dataSource))

  def insert(name: String, createdAt: Long): Task[GroupRow] = {
    val row = GroupRow(0L, name, createdAt)
    run(ctx.run(quote(groupsQ.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))
  }

  def findById(id: Long): Task[Option[GroupRow]] = {
    run(ctx.run(quote(groupsQ.filter(_.id == lift(id))))).map(_.headOption)
  }

  def listForUser(userId: Long): Task[List[(GroupRow, String)]] = {
    val q = quote {
      for {
        m <- membersQ.filter(_.userId == lift(userId))
        g <- groupsQ.join(g => g.id == m.groupId)
      } yield (g, m.role)
    }
    run(ctx.run(q))
  }

  def delete(id: Long): Task[Unit] = {
    run(ctx.run(quote(groupsQ.filter(_.id == lift(id)).delete))).unit
  }
}

object SqliteGroupRepository {
  val live: ZLayer[DataSource, Nothing, GroupRepository] =
    ZLayer.fromFunction((ds: DataSource) => new SqliteGroupRepository(ds): GroupRepository)
}

final class SqliteGroupMemberRepository(dataSource: DataSource) extends GroupMemberRepository {
  private val ctx = new SqliteZioJdbcContext(SnakeCase)
  import ctx._

  private inline def members = quote(querySchema[GroupMemberRow]("group_members"))
  private inline def usersQ  = quote(querySchema[UserRow]("users"))

  private def run[T](q: zio.ZIO[DataSource, Throwable, T]): Task[T] = q.provideEnvironment(ZEnvironment(dataSource))

  def addMember(groupId: Long, userId: Long, role: String, joinedAt: Long): Task[Unit] = {
    run(ctx.run(quote(members.insertValue(lift(GroupMemberRow(groupId, userId, role, joinedAt)))))).unit
  }

  def findRole(groupId: Long, userId: Long): Task[Option[String]] = {
    run(ctx.run(quote(members.filter(m => m.groupId == lift(groupId) && m.userId == lift(userId)).map(_.role)))).map(_.headOption)
  }

  def listForGroup(groupId: Long): Task[List[(GroupMemberRow, String)]] = {
    val q = quote {
      for {
        m <- members.filter(_.groupId == lift(groupId))
        u <- usersQ.join(u => u.id == m.userId)
      } yield (m, u.email)
    }
    run(ctx.run(q))
  }

  def removeMember(groupId: Long, userId: Long): Task[Unit] = {
    run(ctx.run(quote(members.filter(m => m.groupId == lift(groupId) && m.userId == lift(userId)).delete))).unit
  }

  def updateRole(groupId: Long, userId: Long, role: String): Task[Unit] = {
    run(
      ctx.run(quote(members.filter(m => m.groupId == lift(groupId) && m.userId == lift(userId)).update(_.role -> lift(role))))
    ).unit
  }

  def countAdmins(groupId: Long): Task[Long] = {
    run(ctx.run(quote(members.filter(m => m.groupId == lift(groupId) && m.role == lift("admin")).size)))
  }
}

object SqliteGroupMemberRepository {
  val live: ZLayer[DataSource, Nothing, GroupMemberRepository] =
    ZLayer.fromFunction((ds: DataSource) => new SqliteGroupMemberRepository(ds): GroupMemberRepository)
}

final class SqliteGroupPairRepository(dataSource: DataSource) extends GroupPairRepository {
  private val ctx = new SqliteZioJdbcContext(SnakeCase)
  import ctx._

  private inline def pairs = quote(querySchema[GroupPairRow]("group_pairs"))

  private def run[T](q: zio.ZIO[DataSource, Throwable, T]): Task[T] = q.provideEnvironment(ZEnvironment(dataSource))

  def insert(
    groupId: Long,
    source: String,
    target: String,
    createdBy: Long,
    createdByEmail: String,
    createdAt: Long,
  ): Task[GroupPairRow] = {
    val row = GroupPairRow(0L, groupId, source, target, createdBy, createdByEmail, createdAt)
    run(ctx.run(quote(pairs.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))
  }

  def listForGroup(groupId: Long): Task[List[GroupPairRow]] = {
    run(ctx.run(quote(pairs.filter(_.groupId == lift(groupId)).sortBy(_.createdAt))))
  }
}

object SqliteGroupPairRepository {
  val live: ZLayer[DataSource, Nothing, GroupPairRepository] =
    ZLayer.fromFunction((ds: DataSource) => new SqliteGroupPairRepository(ds): GroupPairRepository)
}
