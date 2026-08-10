package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.{AppShell, Labels}
import webapp1.frontend.Page
import webapp1.shared.domain.{TodoItem, TodoStatus}
import webapp1.shared.dto.UpdateTodoStatusRequest
import webapp1.frontend.i18n.I18n
import webapp1.shared.i18n.{MessageKeys, UiKeys}
import webapp1.shared.validation.Validation

object TodoPage {
  def render(): HtmlElement = AppShell.render(Page.Home, new TodoPage().render())
}

private class TodoPage {
  private val itemsVar                      = Var(List.empty[TodoItem])
  private val itemsSignal                   = itemsVar.signal
  private val textVar                       = Var("")
  private val textSignal                    = textVar.signal
  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val loadBus = new EventBus[Unit]()
  private val addBus  = new EventBus[Unit]()
  private val moveBus = new EventBus[(Long, TodoStatus)]()

  // Validation is pure; the effects hang off the resulting stream as observers.
  private val addStream = addBus.events
    .filterWith(inFlightSignal.not)
    .map(_ => Validation.validateNonBlank(textVar.now(), MessageKeys.fieldTodoText).left.map(I18n.resolve))

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.todoTitle)),
      child.maybe <-- errorSignal.map(_.map(renderError)),
      renderAddForm(),
      div(
        cls  := "grid grid-cols-1 md:grid-cols-3 gap-4 mt-4",
        renderColumn(TodoStatus.ToDo),
        renderColumn(TodoStatus.InProgress),
        renderColumn(TodoStatus.Done),
      ),
      loadBus.events.flatMapSwitch(_ => ApiClient.listTodos) -->
        Observer[Either[ApiError, List[TodoItem]]] {
          case Right(items) =>
            Var.set(itemsVar -> items, errorVar -> None)
          case Left(err)    =>
            errorVar.set(Some(err.message))
        },
      addStream -->
        Observer[Either[String, String]] {
          case Left(err) =>
            errorVar.set(Some(err))
          case Right(_)  =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      addStream
        .collect { case Right(text) =>
          text
        }
        .flatMapSwitch(text => ApiClient.createTodo(text)) -->
        Observer[Either[ApiError, TodoItem]] {
          case Right(item) =>
            itemsVar.update(_ :+ item)
            Var.set(inFlightVar -> false, textVar -> "", errorVar -> None)
          case Left(err)   =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      moveBus.events.flatMapSwitch(moveTodo) -->
        Observer[(Long, Either[ApiError, TodoItem])] {
          case (id, Right(updated)) =>
            itemsVar.update(
              _.map(item => {
                if (item.id == id)
                  updated
                else
                  item
              })
            )
            errorVar.set(None)
          case (_, Left(err))       =>
            errorVar.set(Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderAddForm(): HtmlElement = {
    form(
      cls := "flex gap-2",
      onSubmit.preventDefault.mapToUnit --> addBus.writer,
      input(
        cls         := "input flex-1",
        placeholder := I18n.t(UiKeys.todoPlaceholder),
        controlled(value <-- textSignal, onInput.mapToValue --> textVar.writer),
      ),
      button(cls    := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, I18n.t(UiKeys.commonAdd)),
    )
  }

  private def renderColumn(status: TodoStatus): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        h2(cls := "card-title", Labels.todoStatus(status)),
        ul(
          cls  := "list",
          children <--
            itemsSignal
              .map(_.filter(_.status == status))
              .splitSeq(_.id) { itemSignal =>
                renderItem(itemSignal.key, status, itemSignal)
              },
        ),
      ),
    )
  }

  private def renderItem(id: Long, status: TodoStatus, itemSignal: Signal[TodoItem]): HtmlElement = {
    li(
      cls := "list-row cursor-pointer hover:bg-base-200",
      text <-- itemSignal.map(_.text).distinct,
      onClick.mapToUnit --> Observer[Unit](_ => moveBus.emit((id, status.next))),
    )
  }

  // The id travels with the response so the observer can patch the right item without
  // reaching back into a Var mid-stream.
  private def moveTodo(idAndStatus: (Long, TodoStatus)): EventStream[(Long, Either[ApiError, TodoItem])] = {
    val (id, newStatus) = idAndStatus
    ApiClient.updateTodoStatus(id, UpdateTodoStatusRequest(newStatus)).map(result => (id, result))
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
