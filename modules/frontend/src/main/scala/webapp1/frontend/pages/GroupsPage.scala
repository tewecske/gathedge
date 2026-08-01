package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.AppShell
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.Group
import webapp1.shared.dto.CreateGroupRequest
import webapp1.shared.validation.Validation

object GroupsPage {
  def render(): HtmlElement = AppShell.render(Page.Groups, new GroupsPage().render())
}

private class GroupsPage {
  private val groupsVar = Var(List.empty[Group])
  private val groupsSignal = groupsVar.signal
  private val nameVar = Var("")
  private val nameSignal = nameVar.signal
  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal = errorVar.signal
  private val inFlightVar = Var(false)
  private val inFlightSignal = inFlightVar.signal

  private val loadBus = new EventBus[Unit]()
  private val createBus = new EventBus[Unit]()

  // Validation is pure; the effects hang off the resulting stream as observers.
  private val createStream = createBus
    .events
    .filterWith(inFlightSignal.not)
    .map(_ => Validation.validateNonBlank(nameVar.now(), "Group name"))

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", "Groups"),
      child.maybe <-- errorSignal.map(_.map(renderError)),
      renderCreateForm(),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          ul(
            cls := "list",
            children <--
              groupsSignal.splitSeq(_.id) { groupSignal =>
                renderGroupRow(groupSignal.key, groupSignal)
              },
          ),
        ),
      ),
      loadBus.events.flatMapSwitch(_ => ApiClient.get[List[Group]]("/api/groups")) -->
        Observer[Either[ApiError, List[Group]]] {
          case Right(groups) =>
            Var.set(groupsVar -> groups, errorVar -> None)
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      createStream -->
        Observer[Either[String, String]] {
          case Left(err) =>
            errorVar.set(Some(err))
          case Right(_) =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      createStream
        .collect { case Right(name) =>
          name
        }
        .flatMapSwitch(name => ApiClient.post[CreateGroupRequest, Group]("/api/groups", CreateGroupRequest(name))) -->
        Observer[Either[ApiError, Group]] {
          case Right(group) =>
            groupsVar.update(_ :+ group)
            Var.set(inFlightVar -> false, nameVar -> "", errorVar -> None)
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderCreateForm(): HtmlElement = {
    form(
      cls := "flex gap-2",
      onSubmit.preventDefault.mapToUnit --> createBus.writer,
      input(
        cls := "input flex-1",
        placeholder := "New group name",
        controlled(value <-- nameSignal, onInput.mapToValue --> nameVar.writer),
      ),
      button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Create group"),
    )
  }

  private def renderGroupRow(id: Long, groupSignal: Signal[Group]): HtmlElement = {
    li(
      cls := "list-row",
      a(
        cls := "link link-hover flex-1",
        AppRouter.router.navigateTo(Page.GroupDetail(id)),
        text <-- groupSignal.map(_.name).distinct,
      ),
      span(cls := "badge badge-ghost", text <-- groupSignal.map(_.myRole.toString).distinct),
    )
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
