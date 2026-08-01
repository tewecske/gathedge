package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

final class TodoRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with TodoRepository {
  import ctx._

  private inline def todos = quote(querySchema[TodoItemRow]("todo_items"))

  def insert(userId: Long, text: String, status: String, createdAt: Long): Task[TodoItemRow] = {
    val row = TodoItemRow(0L, userId, text, status, createdAt)
    run(ctx.run(quote(todos.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))
  }

  def listForUser(userId: Long): Task[List[TodoItemRow]] = {
    run(ctx.run(quote(todos.filter(_.userId == lift(userId)).sortBy(_.createdAt))))
  }

  def updateStatus(id: Long, userId: Long, status: String): Task[Option[TodoItemRow]] = {
    // Update then read back, in one transaction so the row returned is the one this call wrote
    // rather than whatever a concurrent request left behind in between.
    val queries = {
      for {
        affected <- ctx.run(
          quote(todos.filter(t => t.id == lift(id) && t.userId == lift(userId)).update(_.status -> lift(status)))
        )
        result <-
          if (affected > 0)
            ctx.run(quote(todos.filter(_.id == lift(id)))).map(_.headOption)
          else
            ZIO.none
      } yield result
    }
    transaction(queries)
  }
}

object PostgresTodoRepository {
  val live: ZLayer[DataSource, Nothing, TodoRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new TodoRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): TodoRepository
  )
}

object SqliteTodoRepository {
  val live: ZLayer[DataSource, Nothing, TodoRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new TodoRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): TodoRepository
  )
}

final class GroupRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GroupRepository {
  import ctx._

  private inline def groupsQ = quote(querySchema[GroupRow]("groups"))
  private inline def membersQ = quote(querySchema[GroupMemberRow]("group_members"))

  def insert(name: String, createdAt: Long): Task[GroupRow] = {
    val row = GroupRow(0L, name, createdAt)
    run(ctx.run(quote(groupsQ.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))
  }

  def insertWithCreator(name: String, creatorId: Long, creatorRole: String, createdAt: Long): Task[GroupRow] = {
    val row = GroupRow(0L, name, createdAt)
    // Both writes or neither: a group whose creator's membership row is missing is invisible in
    // "my groups" and, because every operation on it requires membership, impossible to delete.
    // `group_members` is written here rather than through GroupMemberRepository on purpose — see
    // the note on QuillRepository about transactions not composing across repositories.
    val queries = {
      for {
        id <- ctx.run(quote(groupsQ.insertValue(lift(row)).returningGenerated(_.id)))
        _ <- ctx.run(quote(membersQ.insertValue(lift(GroupMemberRow(id, creatorId, creatorRole, createdAt)))))
      } yield id
    }
    transaction(queries).map(id => row.copy(id = id))
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

object PostgresGroupRepository {
  val live: ZLayer[DataSource, Nothing, GroupRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GroupRepository
  )
}

object SqliteGroupRepository {
  val live: ZLayer[DataSource, Nothing, GroupRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GroupRepository
  )
}

final class GroupMemberRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GroupMemberRepository {
  import ctx._

  private inline def members = quote(querySchema[GroupMemberRow]("group_members"))
  private inline def usersQ = quote(querySchema[UserRow]("users"))

  def addMember(groupId: Long, userId: Long, role: String, joinedAt: Long): Task[Unit] = {
    run(ctx.run(quote(members.insertValue(lift(GroupMemberRow(groupId, userId, role, joinedAt)))))).unit
  }

  def findRole(groupId: Long, userId: Long): Task[Option[String]] = {
    run(ctx.run(quote(members.filter(m => m.groupId == lift(groupId) && m.userId == lift(userId)).map(_.role)))).map(
      _.headOption
    )
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
      ctx.run(
        quote(members.filter(m => m.groupId == lift(groupId) && m.userId == lift(userId)).update(_.role -> lift(role)))
      )
    ).unit
  }

  def countAdmins(groupId: Long): Task[Long] = {
    run(ctx.run(quote(members.filter(m => m.groupId == lift(groupId) && m.role == lift("admin")).size)))
  }
}

object PostgresGroupMemberRepository {
  val live: ZLayer[DataSource, Nothing, GroupMemberRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupMemberRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GroupMemberRepository
  )
}

object SqliteGroupMemberRepository {
  val live: ZLayer[DataSource, Nothing, GroupMemberRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupMemberRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GroupMemberRepository
  )
}

final class GroupPairRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with GroupPairRepository {
  import ctx._

  private inline def pairs = quote(querySchema[GroupPairRow]("group_pairs"))

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

object PostgresGroupPairRepository {
  val live: ZLayer[DataSource, Nothing, GroupPairRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupPairRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): GroupPairRepository
  )
}

object SqliteGroupPairRepository {
  val live: ZLayer[DataSource, Nothing, GroupPairRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new GroupPairRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): GroupPairRepository
  )
}
