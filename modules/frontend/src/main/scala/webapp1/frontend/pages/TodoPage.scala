package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.AppShell
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.{TodoItem, TodoStatus, User}
import webapp1.shared.dto.{CreateTodoRequest, UpdateTodoStatusRequest}

object TodoPage {
  def render(user: User): HtmlElement = AppShell.render(user, Page.Home, new TodoPage().render())
}

private class TodoPage {
  private val itemsVar = Var(List.empty[TodoItem])
  private val textVar  = Var("")
  private val errorVar: Var[Option[String]] = Var(None)

  private val loadBus = new EventBus[Unit]()
  private val addBus  = new EventBus[Unit]()
  private val moveBus = new EventBus[(Long, TodoStatus)]()

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", "TODO"),
      child.maybe <-- errorVar.signal.map(_.map(renderError)),
      renderAddForm(),
      div(
        cls := "grid grid-cols-1 md:grid-cols-3 gap-4 mt-4",
        renderColumn("To Do", TodoStatus.ToDo),
        renderColumn("In Progress", TodoStatus.InProgress),
        renderColumn("Done", TodoStatus.Done),
      ),
      loadBus.events.flatMapSwitch(_ => ApiClient.get[List[TodoItem]]("/api/todos")) --> Observer[Either[ApiError, List[TodoItem]]] {
        case Right(items) => itemsVar.set(items)
        case Left(err)    => errorVar.set(Some(err.message))
      },
      addBus.events.flatMapSwitch(_ => addTodo()) --> Observer[Unit](_ => ()),
      moveBus.events.flatMapSwitch(moveTodo) --> Observer[Unit](_ => ()),
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderAddForm(): HtmlElement = {
    div(
      cls := "flex gap-2",
      input(
        cls := "input flex-1",
        placeholder := "New to-do item",
        value <-- textVar.signal,
        onInput.mapToValue --> textVar.writer,
      ),
      button(cls := "btn btn-primary", typ := "button", "Add", onClick.mapToUnit --> addBus.writer),
    )
  }

  private def renderColumn(title: String, status: TodoStatus): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        h2(cls := "card-title", title),
        ul(
          cls := "list",
          children <-- itemsVar.signal.map(_.filter(_.status == status)).splitSeq(_.id) { itemSignal =>
            renderItem(itemSignal.key, status, itemSignal)
          },
        ),
      ),
    )
  }

  private def renderItem(id: Long, status: TodoStatus, itemSignal: Signal[TodoItem]): HtmlElement = {
    li(
      cls := "list-row cursor-pointer hover:bg-base-200",
      text <-- itemSignal.map(_.text),
      onClick.mapToUnit --> Observer[Unit](_ => moveBus.emit((id, status.next))),
    )
  }

  private def addTodo() = {
    val text = textVar.now()
    if (text.trim.isEmpty) {
      EventStream.fromValue((), emitOnce = true)
    } else {
      ApiClient.post[CreateTodoRequest, TodoItem]("/api/todos", CreateTodoRequest(text)).map {
        case Right(item) =>
          itemsVar.update(_ :+ item)
          textVar.set("")
          errorVar.set(None)
        case Left(err) => errorVar.set(Some(err.message))
      }
    }
  }

  private def moveTodo(idAndStatus: (Long, TodoStatus)) = {
    val (id, newStatus) = idAndStatus
    ApiClient.put[UpdateTodoStatusRequest, TodoItem](s"/api/todos/$id/status", UpdateTodoStatusRequest(newStatus)).map {
      case Right(updated) => itemsVar.update(_.map(item => if (item.id == id) updated else item))
      case Left(err)      => errorVar.set(Some(err.message))
    }
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
