package webapp1.backend.db

import io.getquill.*
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

trait TodoRepository {
  def insert(userId: Long, text: String, status: String, createdAt: Long): Task[TodoItemRow]
  def listForUser(userId: Long): Task[List[TodoItemRow]]

  /** Updates status only if the row exists and belongs to `userId`. Returns the updated row, or None if not found/not
    * owned.
    */
  def updateStatus(id: Long, userId: Long, status: String): Task[Option[TodoItemRow]]
}

object TodoRepository {
  def insert(userId: Long, text: String, status: String, createdAt: Long): RIO[TodoRepository, TodoItemRow] =
    ZIO.serviceWithZIO[TodoRepository](_.insert(userId, text, status, createdAt))

  def listForUser(userId: Long): RIO[TodoRepository, List[TodoItemRow]] =
    ZIO.serviceWithZIO[TodoRepository](_.listForUser(userId))

  def updateStatus(id: Long, userId: Long, status: String): RIO[TodoRepository, Option[TodoItemRow]] =
    ZIO.serviceWithZIO[TodoRepository](_.updateStatus(id, userId, status))

  val live: ZLayer[DataSource, Nothing, TodoRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new TodoRepositoryLive(ds, new PostgresZioJdbcContext(SnakeCase)): TodoRepository
  )

  /** SQLite backs tests only — production is always Postgres, hence `test` rather than `live`. */
  val test: ZLayer[DataSource, Nothing, TodoRepository] = ZLayer.fromFunction((ds: DataSource) =>
    new TodoRepositoryLive(ds, new SqliteZioJdbcContext(SnakeCase)): TodoRepository
  )
}

final class TodoRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext)
    with TodoRepository {
  import ctx._

  private inline def todos = quote(querySchema[TodoItemRow]("todo_items"))

  def insert(userId: Long, text: String, status: String, createdAt: Long): Task[TodoItemRow] = {
    val row = TodoItemRow(0L, userId, text, status, createdAt)
    logged(run(ctx.run(quote(todos.insertValue(lift(row)).returningGenerated(_.id)))).map(id => row.copy(id = id))) {
      todo =>
        s"todos.insert id=${todo.id} userId=$userId status=$status"
    }
  }

  def listForUser(userId: Long): Task[List[TodoItemRow]] = {
    logged(run(ctx.run(quote(todos.filter(_.userId == lift(userId)).sortBy(_.createdAt))))) { rows =>
      s"todos.listForUser userId=$userId count=${rows.size}"
    }
  }

  def updateStatus(id: Long, userId: Long, status: String): Task[Option[TodoItemRow]] = {
    // Update then read back, in one transaction so the row returned is the one this call wrote
    // rather than whatever a concurrent request left behind in between.
    val queries = {
      for {
        affected <- ctx.run(
                      quote(
                        todos.filter(t => t.id == lift(id) && t.userId == lift(userId)).update(_.status -> lift(status))
                      )
                    )
        result   <-
          if (affected > 0)
            ctx.run(quote(todos.filter(_.id == lift(id)))).map(_.headOption)
          else
            ZIO.none
      } yield result
    }
    logged(transaction(queries)) { updated =>
      s"todos.updateStatus id=$id userId=$userId status=$status updated=${updated.isDefined}"
    }
  }
}
