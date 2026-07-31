package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.AppShell
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.{Group, User}
import webapp1.shared.dto.CreateGroupRequest

object GroupsPage {
  def render(user: User): HtmlElement = AppShell.render(user, Page.Groups, new GroupsPage().render())
}

private class GroupsPage {
  private val groupsVar = Var(List.empty[Group])
  private val nameVar   = Var("")
  private val errorVar: Var[Option[String]] = Var(None)

  private val loadBus   = new EventBus[Unit]()
  private val createBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      onMountCallback(_ => loadBus.emit(())),
      h1(cls := "text-2xl font-bold mb-4", "Groups"),
      child.maybe <-- errorVar.signal.map(_.map(renderError)),
      renderCreateForm(),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          ul(
            cls := "list",
            children <-- groupsVar.signal.splitSeq(_.id) { groupSignal => renderGroupRow(groupSignal.key, groupSignal) },
          ),
        ),
      ),
      loadBus.events.flatMapSwitch(_ => ApiClient.get[List[Group]]("/api/groups")) --> Observer[Either[ApiError, List[Group]]] {
        case Right(groups) => groupsVar.set(groups)
        case Left(err)     => errorVar.set(Some(err.message))
      },
      createBus.events.flatMapSwitch(_ => createGroup()) --> Observer[Unit](_ => ()),
    )
  }

  private def renderCreateForm(): HtmlElement = {
    div(
      cls := "flex gap-2",
      input(
        cls := "input flex-1",
        placeholder := "New group name",
        value <-- nameVar.signal,
        onInput.mapToValue --> nameVar.writer,
      ),
      button(cls := "btn btn-primary", typ := "button", "Create group", onClick.mapToUnit --> createBus.writer),
    )
  }

  private def renderGroupRow(id: Long, groupSignal: Signal[Group]): HtmlElement = {
    li(
      cls := "list-row",
      a(
        cls := "link link-hover flex-1",
        AppRouter.router.navigateTo(Page.GroupDetail(id)),
        text <-- groupSignal.map(_.name),
      ),
      span(cls := "badge badge-ghost", text <-- groupSignal.map(_.myRole.toString)),
    )
  }

  private def createGroup() = {
    val name = nameVar.now()
    if (name.trim.isEmpty) {
      EventStream.fromValue((), emitOnce = true)
    } else {
      ApiClient.post[CreateGroupRequest, Group]("/api/groups", CreateGroupRequest(name)).map {
        case Right(group) =>
          groupsVar.update(_ :+ group)
          nameVar.set("")
          errorVar.set(None)
        case Left(err) => errorVar.set(Some(err.message))
      }
    }
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
