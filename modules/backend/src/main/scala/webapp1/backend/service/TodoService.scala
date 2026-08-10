package webapp1.backend.service

import webapp1.backend.db.{TodoItemRow, TodoRepository}
import webapp1.shared.domain.{TodoItem, TodoStatus}
import webapp1.shared.i18n.{MessageKeys, MessageRef}
import webapp1.shared.validation.Validation
import zio.*

import java.util.concurrent.TimeUnit

enum TodoFailure {
  case ValidationError(error: MessageRef)
  case NotFound
}

trait TodoService {
  def addTodo(userId: Long, text: String): IO[TodoFailure, TodoItem]
  def listTodos(userId: Long): UIO[List[TodoItem]]
  def moveTodo(userId: Long, id: Long, newStatus: TodoStatus): IO[TodoFailure, TodoItem]
}

object TodoService {
  def addTodo(userId: Long, text: String): ZIO[TodoService, TodoFailure, TodoItem] =
    ZIO.serviceWithZIO[TodoService](_.addTodo(userId, text))

  def listTodos(userId: Long): URIO[TodoService, List[TodoItem]] =
    ZIO.serviceWithZIO[TodoService](_.listTodos(userId))

  def moveTodo(userId: Long, id: Long, newStatus: TodoStatus): ZIO[TodoService, TodoFailure, TodoItem] =
    ZIO.serviceWithZIO[TodoService](_.moveTodo(userId, id, newStatus))

  val live: URLayer[TodoRepository, TodoService] = ZLayer.fromFunction(TodoServiceLive.apply)
}

final case class TodoServiceLive(repo: TodoRepository) extends TodoService {

  private def toDomain(row: TodoItemRow): TodoItem = {
    TodoItem(row.id, row.text, TodoStatus.fromString(row.status).getOrElse(TodoStatus.ToDo), row.createdAt.toString)
  }

  def addTodo(userId: Long, text: String): IO[TodoFailure, TodoItem] = {
    Validation.validateNonBlank(text, MessageKeys.fieldTodoText) match {
      case Left(err)        =>
        ZIO.fail(TodoFailure.ValidationError(err))
      case Right(validText) =>
        for {
          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
          row <- repo.insert(userId, validText, TodoStatus.toDbString(TodoStatus.ToDo), now).orDie
        } yield toDomain(row)
    }
  }

  def listTodos(userId: Long): UIO[List[TodoItem]] = {
    repo.listForUser(userId).orDie.map(_.map(toDomain))
  }

  def moveTodo(userId: Long, id: Long, newStatus: TodoStatus): IO[TodoFailure, TodoItem] = {
    repo
      .updateStatus(id, userId, TodoStatus.toDbString(newStatus))
      .orDie
      .flatMap {
        case Some(row) =>
          ZIO.succeed(toDomain(row))
        case None      =>
          ZIO.fail(TodoFailure.NotFound)
      }
  }
}
