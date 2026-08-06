package webapp1.backend.service

import webapp1.backend.TestDataSource
import webapp1.backend.db.TodoRepository
import webapp1.shared.domain.TodoStatus
import zio._
import zio.test._

object TodoServiceSpec extends ZIOSpecDefault {

  private val layer: ZLayer[Any, Throwable, TodoService] =
    (TestDataSource.sqlite >>> TodoRepository.test) >>> TodoService.live

  def spec = suite("TodoService (SQLite)")(
    test("rejects a blank todo (no-op)") {
      for {
        result <- TodoService.addTodo(1L, "   ").either
      } yield assertTrue(result.isLeft)
    },
    test("adds a todo starting in To Do, then cycles through statuses") {
      for {
        added  <- TodoService.addTodo(1L, "write plan")
        moved1 <- TodoService.moveTodo(1L, added.id, TodoStatus.InProgress)
        moved2 <- TodoService.moveTodo(1L, moved1.id, TodoStatus.Done)
        listed <- TodoService.listTodos(1L)
      } yield assertTrue(
        added.status == TodoStatus.ToDo,
        moved1.status == TodoStatus.InProgress,
        moved2.status == TodoStatus.Done,
        listed.map(_.id) == List(added.id),
      )
    },
    test("moving another user's todo fails with NotFound (private to the owning user)") {
      for {
        added  <- TodoService.addTodo(1L, "owned by user 1")
        result <- TodoService.moveTodo(2L, added.id, TodoStatus.Done).either
      } yield assertTrue(result == Left(TodoFailure.NotFound))
    },
  ).provide(layer)
}
