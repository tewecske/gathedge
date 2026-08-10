package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.{AppShell, FormField}
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.Group
import webapp1.shared.dto.CreateGroupRequest
import webapp1.frontend.i18n.I18n
import webapp1.shared.i18n.MessageKeys
import webapp1.shared.validation.Validation

object GroupsPage {
  def render(): HtmlElement = AppShell.render(Page.Groups, new GroupsPage().render())
}

/** State of the create-group form. The error is derived rather than stored, and `showErrors` keeps it hidden until the
  * first submit attempt.
  */
private case class CreateGroupForm(name: String = "", showErrors: Boolean = false) {
  def nameError: Option[String] = I18n.errorOf(Validation.validateNonBlank(name, MessageKeys.fieldGroupName))

  def displayError(error: CreateGroupForm => Option[String]): Option[String] = {
    if (showErrors)
      error(this)
    else
      None
  }

  /** `Some` exactly when the form is valid, so it doubles as the validity check. */
  def toRequest: Option[CreateGroupRequest] = {
    Validation.validateNonBlank(name, MessageKeys.fieldGroupName).toOption.map(CreateGroupRequest(_))
  }
}

private class GroupsPage {
  private val groupsVar    = Var(List.empty[Group])
  private val groupsSignal = groupsVar.signal

  private val formVar         = Var(CreateGroupForm())
  private val nameVar         = formVar.zoom(_.name)((form, name) => form.copy(name = name))
  private val nameErrorSignal = formVar.signal.map(_.displayError(_.nameError))

  // Server-side failures only; the field-level problem renders next to its input.
  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val loadBus   = new EventBus[Unit]()
  private val createBus = new EventBus[Unit]()

  // Validation is pure; the effects hang off the resulting stream as observers.
  private val createStream = createBus.events.filterWith(inFlightSignal.not).map(_ => formVar.now().toRequest)

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", "Groups"),
      child.maybe <-- errorSignal.map(_.map(renderError)),
      renderCreateForm(),
      div(
        cls  := "card bg-base-100 shadow mt-4",
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
      loadBus.events.flatMapSwitch(_ => ApiClient.listGroups) -->
        Observer[Either[ApiError, List[Group]]] {
          case Right(groups) =>
            Var.set(groupsVar -> groups, errorVar -> None)
          case Left(err)     =>
            errorVar.set(Some(err.message))
        },
      createStream -->
        Observer[Option[CreateGroupRequest]] {
          case None    =>
            formVar.update(_.copy(showErrors = true))
          case Some(_) =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      createStream
        .collect { case Some(request) =>
          request
        }
        .flatMapSwitch(request => ApiClient.createGroup(request)) -->
        Observer[Either[ApiError, Group]] {
          case Right(group) =>
            groupsVar.update(_ :+ group)
            Var.set(inFlightVar -> false, formVar -> CreateGroupForm(), errorVar -> None)
          case Left(err)    =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderCreateForm(): HtmlElement = {
    form(
      cls := "flex gap-2 items-start",
      onSubmit.preventDefault.mapToUnit --> createBus.writer,
      FormField.render(nameErrorSignal)(
        cls      := "flex-1",
        input(
          cls         := "input w-full",
          cls("input-error") <-- nameErrorSignal.map(_.nonEmpty),
          placeholder := "New group name",
          controlled(value <-- nameVar.signal, onInput.mapToValue --> nameVar.writer),
        ),
      ),
      button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Create group"),
    )
  }

  private def renderGroupRow(id: Long, groupSignal: Signal[Group]): HtmlElement = {
    li(
      cls := "list-row",
      a(
        cls    := "link link-hover flex-1",
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
