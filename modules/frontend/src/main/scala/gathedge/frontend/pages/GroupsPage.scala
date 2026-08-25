package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GroupApiClient}
import gathedge.frontend.components.{Alert, AppShell}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{Group, GroupRole}
import gathedge.shared.dto.GroupDetail
import gathedge.shared.i18n.UiKeys

/** Browsing every group, creating one (the caller becomes its sole admin), and joining one by invite code. See
  * `GroupDetailPage` for one group's roster/invite-code/tag-list detail.
  */
object GroupsPage {

  def render(): HtmlElement = {
    AppShell.render(Page.Groups, new GroupsPage().render())
  }
}

private class GroupsPage {

  private val groupsVar = Var(List.empty[Group])

  private val nameInputVar = Var("")
  private val codeInputVar = Var("")

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)
  private val creatingVar                    = Var(false)
  private val joiningVar                     = Var(false)

  private val reloadBus = new EventBus[Unit]()
  private val createBus = new EventBus[Unit]()
  private val joinBus   = new EventBus[Unit]()

  private val createStream = createBus.events.filterWith(creatingVar.signal.not).sample(nameInputVar.signal)
  private val joinStream   = joinBus.events.filterWith(joiningVar.signal.not).sample(codeInputVar.signal)

  def render(): HtmlElement = {
    div(
      cls := "p-4 max-w-2xl flex flex-col gap-6",
      h1(cls := "text-2xl font-bold", I18n.t(UiKeys.groupsTitle)),
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(noticeVar.signal),
      renderForms(),
      renderList(),
      reloadBus.events.flatMapSwitch(_ => GroupApiClient.list()) -->
        Observer[Either[ApiError, List[Group]]] {
          case Right(groups) =>
            groupsVar.set(groups)
          case Left(err)     =>
            errorVar.set(Some(err.message))
        },
      createStream --> Observer[String](_ => Var.set(creatingVar -> true, errorVar -> None, noticeVar -> None)),
      createStream.flatMapSwitch(name => GroupApiClient.create(name)) -->
        Observer[Either[ApiError, GroupDetail]] {
          case Right(created) =>
            Var.set(creatingVar -> false, nameInputVar -> "")
            AppRouter.router.pushState(Page.GroupDetail(created.id))
          case Left(err)      =>
            Var.set(creatingVar -> false, errorVar -> Some(err.message))
        },
      joinStream --> Observer[String](_ => Var.set(joiningVar -> true, errorVar -> None, noticeVar -> None)),
      joinStream.flatMapSwitch(code => GroupApiClient.join(code)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(joiningVar -> false, codeInputVar -> "", noticeVar -> Some(I18n.t(UiKeys.groupsJoinSuccess)))
            reloadBus.emit(())
          case Left(err) =>
            Var.set(joiningVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def renderForms(): HtmlElement = {
    div(
      cls := "flex flex-col sm:flex-row gap-4",
      form(
        cls := "card bg-base-100 shadow flex-1",
        onSubmit.preventDefault.mapToUnit --> createBus.writer,
        div(
          cls := "card-body",
          fieldSet(
            cls := "fieldset",
            label(cls := "fieldset-legend", I18n.t(UiKeys.groupsCreateLabel)),
            div(
              cls     := "flex gap-2",
              input(
                cls         := "input flex-1",
                placeholder := I18n.t(UiKeys.groupsCreatePlaceholder),
                controlled(value <-- nameInputVar.signal, onInput.mapToValue --> nameInputVar.writer),
              ),
              button(
                cls         := "btn btn-primary",
                typ         := "submit",
                disabled <-- creatingVar.signal,
                I18n.t(UiKeys.groupsCreateButton),
              ),
            ),
          ),
        ),
      ),
      form(
        cls := "card bg-base-100 shadow flex-1",
        onSubmit.preventDefault.mapToUnit --> joinBus.writer,
        div(
          cls := "card-body",
          fieldSet(
            cls := "fieldset",
            label(cls := "fieldset-legend", I18n.t(UiKeys.groupsJoinLabel)),
            div(
              cls     := "flex gap-2",
              input(
                cls         := "input flex-1",
                placeholder := I18n.t(UiKeys.groupsJoinPlaceholder),
                controlled(value <-- codeInputVar.signal, onInput.mapToValue --> codeInputVar.writer),
              ),
              button(
                cls         := "btn btn-primary",
                typ         := "submit",
                disabled <-- joiningVar.signal,
                I18n.t(UiKeys.groupsJoinButton),
              ),
            ),
          ),
        ),
      ),
    )
  }

  private def renderList(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        child.maybe <--
          groupsVar.signal.map(list =>
            Option.when(list.isEmpty)(p(cls := "text-sm opacity-70", I18n.t(UiKeys.groupsEmpty)))
          ),
        div(
          cls := "overflow-x-auto",
          table(
            cls := "table",
            thead(
              tr(
                th(I18n.t(UiKeys.groupsColName)),
                th(I18n.t(UiKeys.groupsColMembers)),
                th(I18n.t(UiKeys.groupsColTags)),
                th(),
                th(),
              )
            ),
            tbody(children <-- groupsVar.signal.map(_.map(renderRow))),
          ),
        ),
      ),
    )
  }

  private def renderRow(group: Group): HtmlElement = {
    tr(
      td(group.name),
      td(I18n.plural(UiKeys.groupsMemberCount, group.memberCount)),
      td(I18n.plural(UiKeys.groupsTagCount, group.tagCount)),
      td(
        group.viewerRole.map {
          case GroupRole.Admin  => span(cls := "badge badge-primary", I18n.t(UiKeys.groupsRoleAdmin))
          case GroupRole.Member => span(cls := "badge", I18n.t(UiKeys.groupsRoleMember))
        }
      ),
      td(
        a(
          cls := "btn btn-sm",
          AppRouter.router.navigateTo(Page.GroupDetail(group.id)),
          I18n.t(UiKeys.groupsViewButton),
        )
      ),
    )
  }
}
