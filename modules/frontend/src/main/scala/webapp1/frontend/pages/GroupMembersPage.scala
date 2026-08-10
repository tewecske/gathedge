package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.{Alert, AppShell, GroupSubmenu, Labels}
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.{Group, GroupMember, GroupRole}
import webapp1.shared.dto.{InviteMemberRequest, UpdateRoleRequest}
import webapp1.frontend.i18n.I18n
import webapp1.shared.i18n.{MessageKeys, UiKeys}
import webapp1.shared.validation.Validation

object GroupMembersPage {
  def render(groupId: Long): HtmlElement = AppShell.render(
    Page.GroupMembers(groupId),
    new GroupMembersPage(groupId).render(),
  )
}

/** Group admin-only member management, split out of [[GroupDetailPage]] into its own route/view (linked from
  * [[GroupSubmenu]]) so it's not cluttering the group overview for non-admin members. Group-admin status is only known
  * once the group itself has loaded, so the access gate below is evaluated post-load rather than via [[Page.guardFor]].
  */
private class GroupMembersPage(groupId: Long) {
  private val groupVar      = Var(Option.empty[Group])
  private val groupSignal   = groupVar.signal
  private val membersVar    = Var(List.empty[GroupMember])
  private val membersSignal = membersVar.signal

  private val inviteEmailVar    = Var("")
  private val inviteEmailSignal = inviteEmailVar.signal
  private val inviteRoleVar     = Var[GroupRole](GroupRole.ReadWrite)
  private val inviteRoleSignal  = inviteRoleVar.signal

  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal
  private val infoVar: Var[Option[String]]  = Var(None)
  private val infoSignal                    = infoVar.signal
  private val inFlightVar                   = Var(false)
  private val inFlightSignal                = inFlightVar.signal

  private val loadBus         = new EventBus[Unit]()
  private val inviteBus       = new EventBus[Unit]()
  private val removeMemberBus = new EventBus[Long]()
  private val roleChangeBus   = new EventBus[(Long, GroupRole)]()

  private val loadStream   = loadBus.events
  // Validation is pure; the effects hang off the resulting stream as observers.
  private val inviteStream = inviteBus.events.filterWith(inFlightSignal.not).map(_ => validateInvite())

  def render(): HtmlElement = {
    div(
      div(
        cls := "mb-4",
        a(cls := "link", AppRouter.router.navigateTo(Page.GroupDetail(groupId)), I18n.t(UiKeys.membersBackToGroup)),
      ),
      h1(
        cls := "text-2xl font-bold mb-4",
        text <--
          groupSignal
            .map(_.map(g => I18n.t(UiKeys.membersTitleFor, g.name)).getOrElse(I18n.t(UiKeys.membersTitle)))
            .distinct,
      ),
      child.maybe <-- groupSignal.map(_.map(g => GroupSubmenu.render(groupId, Page.GroupMembers(groupId), g.myRole))),
      Alert.maybeError(errorSignal),
      Alert.maybeInfo(infoSignal),
      child.maybe <--
        groupSignal.map {
          case Some(g) if g.myRole.isAdmin =>
            Some(renderMembersSection())
          case Some(_)                     =>
            Some(renderForbidden())
          case None                        =>
            None
        },
      // The group and its members load in parallel and share one error slot, so clear it when a
      // fresh load starts — otherwise a stale failure outlives the request that produced it.
      loadStream --> Observer[Unit](_ => errorVar.set(None)),
      loadStream.flatMapSwitch(_ => ApiClient.getGroup(groupId)) -->
        Observer[Either[ApiError, Group]] {
          case Right(g)  =>
            groupVar.set(Some(g))
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      loadStream.flatMapSwitch(_ => ApiClient.listMembers(groupId)) -->
        Observer[Either[ApiError, List[GroupMember]]] {
          case Right(items) =>
            membersVar.set(items)
          case Left(err)    =>
            errorVar.set(Some(err.message))
        },
      inviteStream -->
        Observer[Either[String, InviteMemberRequest]] {
          case Left(err) =>
            errorVar.set(Some(err))
          case Right(_)  =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      inviteStream
        .collect { case Right(request) =>
          request
        }
        .flatMapSwitch(request => ApiClient.inviteMember(groupId, request).map(result => (request, result))) -->
        Observer[(InviteMemberRequest, Either[ApiError, Unit])] {
          case (request, Right(_)) =>
            Var.set(
              inFlightVar    -> false,
              inviteEmailVar -> "",
              errorVar       -> None,
              infoVar        -> Some(I18n.t(UiKeys.membersInvited, request.email)),
            )
          case (_, Left(err))      =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      // Auto-dismiss the confirmation; flatMapSwitch cancels the pending timer if a newer
      // message arrives, so the last one always gets its full four seconds.
      infoSignal.updates.filter(_.isDefined).flatMapSwitch(_ => EventStream.delay(4000)) -->
        Observer[Unit](_ => infoVar.set(None)),
      removeMemberBus.events.flatMapSwitch(removeMember) -->
        Observer[(Long, Either[ApiError, Unit])] {
          case (userId, Right(_)) =>
            membersVar.update(_.filterNot(_.userId == userId))
            errorVar.set(None)
          case (_, Left(err))     =>
            errorVar.set(Some(err.message))
        },
      roleChangeBus.events.flatMapSwitch(changeRole) -->
        Observer[((Long, GroupRole), Either[ApiError, Unit])] {
          case ((userId, role), Right(_)) =>
            membersVar.update(
              _.map(m => {
                if (m.userId == userId)
                  m.copy(role = role)
                else
                  m
              })
            )
            errorVar.set(None)
          case (_, Left(err))             =>
            // membersVar is untouched, so the `controlled` select below snaps back to the
            // role that is actually stored rather than showing the failed selection.
            errorVar.set(Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderForbidden(): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(I18n.t(UiKeys.membersForbidden)))
  }

  private def renderMembersSection(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        div(
          cls := "overflow-x-auto",
          table(
            cls := "table",
            thead(tr(th(I18n.t(MessageKeys.fieldEmail)), th(I18n.t(UiKeys.membersColRole)), th(""))),
            tbody(
              children <--
                membersSignal.splitSeq(_.userId) { memberSignal =>
                  renderMemberRow(memberSignal.key, memberSignal)
                }
            ),
          ),
        ),
        renderInviteForm(),
      ),
    )
  }

  private def renderMemberRow(userId: Long, memberSignal: Signal[GroupMember]): HtmlElement = {
    tr(
      td(text <-- memberSignal.map(_.email).distinct),
      td(renderRoleSelect(userId, memberSignal)),
      td(
        button(
          cls := "btn btn-ghost btn-xs",
          typ := "button",
          I18n.t(UiKeys.commonRemove),
          onClick.mapToUnit --> Observer[Unit](_ => removeMemberBus.emit(userId)),
        )
      ),
    )
  }

  private def renderRoleSelect(userId: Long, memberSignal: Signal[GroupMember]): HtmlElement = {
    select(
      cls := "select select-sm",
      // The `value` stays the enum's `toString`: it is what `controlled` round-trips and what the
      // lookup below matches on. Only the label is worded.
      GroupRole.all.map(role => option(value := role.toString, Labels.role(role))),
      controlled(
        value <-- memberSignal.map(_.role.toString).distinct,
        onChange.mapToValue -->
          Observer[String] { roleStr =>
            GroupRole.all.find(_.toString == roleStr).foreach(role => roleChangeBus.emit((userId, role)))
          },
      ),
    )
  }

  private def renderInviteForm(): HtmlElement = {
    form(
      cls := "flex gap-2 mt-4",
      onSubmit.preventDefault.mapToUnit --> inviteBus.writer,
      input(
        cls         := "input flex-1",
        typ         := "email",
        placeholder := I18n.t(UiKeys.membersInvitePlaceholder),
        controlled(value <-- inviteEmailSignal, onInput.mapToValue --> inviteEmailVar.writer),
      ),
      select(
        cls         := "select",
        GroupRole.all.map(role => option(value := role.toString, Labels.role(role))),
        controlled(
          value <-- inviteRoleSignal.map(_.toString).distinct,
          onChange.mapToValue -->
            Observer[String] { roleStr =>
              GroupRole.all.find(_.toString == roleStr).foreach(inviteRoleVar.set)
            },
        ),
      ),
      button(cls    := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, I18n.t(UiKeys.membersInvite)),
    )
  }

  private def validateInvite(): Either[String, InviteMemberRequest] = {
    Validation
      .validateEmail(inviteEmailVar.now())
      .left
      .map(I18n.resolve)
      .map(email => InviteMemberRequest(email, inviteRoleVar.now()))
  }

  // The key travels with the response so the observer can patch the right row without
  // reaching back into a Var mid-stream.
  private def removeMember(userId: Long): EventStream[(Long, Either[ApiError, Unit])] = {
    ApiClient.removeMember(groupId, userId).map(result => (userId, result))
  }

  private def changeRole(userIdAndRole: (Long, GroupRole)): EventStream[((Long, GroupRole), Either[ApiError, Unit])] = {
    val (userId, role) = userIdAndRole
    ApiClient.updateMemberRole(groupId, userId, UpdateRoleRequest(role)).map(result => (userIdAndRole, result))
  }

}
