package webapp1.backend.service

import webapp1.backend.TestDataSource
import webapp1.backend.db.{SqliteTodoRepository, TodoRepository}
import webapp1.shared.domain.TodoStatus
import zio._
import zio.test._

object TodoServiceSpec extends ZIOSpecDefault {

  private val layer: ZLayer[Any, Throwable, TodoService] =
    (TestDataSource.sqlite >>> SqliteTodoRepository.test) >>> TodoService.live

  def spec = suite("TodoService (SQLite)")(
    test("rejects a blank todo (no-op)") {
      for {
        service <- ZIO.service[TodoService]
        result  <- service.addTodo(1L, "   ").either
      } yield assertTrue(result.isLeft)
    },
    test("adds a todo starting in To Do, then cycles through statuses") {
      for {
        service <- ZIO.service[TodoService]
        added   <- service.addTodo(1L, "write plan")
        moved1  <- service.moveTodo(1L, added.id, TodoStatus.InProgress)
        moved2  <- service.moveTodo(1L, moved1.id, TodoStatus.Done)
        listed  <- service.listTodos(1L)
      } yield assertTrue(
        added.status == TodoStatus.ToDo,
        moved1.status == TodoStatus.InProgress,
        moved2.status == TodoStatus.Done,
        listed.map(_.id) == List(added.id),
      )
    },
    test("moving another user's todo fails with NotFound (private to the owning user)") {
      for {
        service <- ZIO.service[TodoService]
        added   <- service.addTodo(1L, "owned by user 1")
        result  <- service.moveTodo(2L, added.id, TodoStatus.Done).either
      } yield assertTrue(result == Left(TodoFailure.NotFound))
    },
  ).provide(layer)
}
