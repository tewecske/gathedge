package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.{AppShell, GroupSubmenu}
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.{Group, GroupMember, GroupRole}
import webapp1.shared.dto.{InviteMemberRequest, UpdateRoleRequest}
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
  private val groupVar = Var(Option.empty[Group])
  private val groupSignal = groupVar.signal
  private val membersVar = Var(List.empty[GroupMember])
  private val membersSignal = membersVar.signal

  private val inviteEmailVar = Var("")
  private val inviteEmailSignal = inviteEmailVar.signal
  private val inviteRoleVar = Var[GroupRole](GroupRole.ReadWrite)
  private val inviteRoleSignal = inviteRoleVar.signal

  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal = errorVar.signal
  private val infoVar: Var[Option[String]] = Var(None)
  private val infoSignal = infoVar.signal
  private val inFlightVar = Var(false)
  private val inFlightSignal = inFlightVar.signal

  private val loadBus = new EventBus[Unit]()
  private val inviteBus = new EventBus[Unit]()
  private val removeMemberBus = new EventBus[Long]()
  private val roleChangeBus = new EventBus[(Long, GroupRole)]()

  private val loadStream = loadBus.events
  // Validation is pure; the effects hang off the resulting stream as observers.
  private val inviteStream = inviteBus.events.filterWith(inFlightSignal.not).map(_ => validateInvite())

  def render(): HtmlElement = {
    div(
      div(cls := "mb-4", a(cls := "link", AppRouter.router.navigateTo(Page.GroupDetail(groupId)), "← Back to group")),
      h1(
        cls := "text-2xl font-bold mb-4",
        text <-- groupSignal.map(_.map(g => s"${g.name} — Members").getOrElse("Members")).distinct,
      ),
      child.maybe <-- groupSignal.map(_.map(g => GroupSubmenu.render(groupId, Page.GroupMembers(groupId), g.myRole))),
      child.maybe <-- errorSignal.map(_.map(msg => renderAlert("alert-error", msg))),
      child.maybe <-- infoSignal.map(_.map(msg => renderAlert("alert-info", msg))),
      child.maybe <--
        groupSignal.map {
          case Some(g) if g.myRole.isAdmin =>
            Some(renderMembersSection())
          case Some(_) =>
            Some(renderForbidden())
          case None =>
            None
        },
      // The group and its members load in parallel and share one error slot, so clear it when a
      // fresh load starts — otherwise a stale failure outlives the request that produced it.
      loadStream --> Observer[Unit](_ => errorVar.set(None)),
      loadStream.flatMapSwitch(_ => ApiClient.get[Group](s"/api/groups/$groupId")) -->
        Observer[Either[ApiError, Group]] {
          case Right(g) =>
            groupVar.set(Some(g))
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      loadStream.flatMapSwitch(_ => ApiClient.get[List[GroupMember]](s"/api/groups/$groupId/members")) -->
        Observer[Either[ApiError, List[GroupMember]]] {
          case Right(items) =>
            membersVar.set(items)
          case Left(err) =>
            errorVar.set(Some(err.message))
        },
      inviteStream -->
        Observer[Either[String, InviteMemberRequest]] {
          case Left(err) =>
            errorVar.set(Some(err))
          case Right(_) =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      inviteStream
        .collect { case Right(request) =>
          request
        }
        .flatMapSwitch(request =>
          ApiClient.postBodyNoContent(s"/api/groups/$groupId/invitations", request).map(result => (request, result))
        ) -->
        Observer[(InviteMemberRequest, Either[ApiError, Unit])] {
          case (request, Right(_)) =>
            Var.set(
              inFlightVar -> false,
              inviteEmailVar -> "",
              errorVar -> None,
              infoVar -> Some(s"Invited ${request.email}"),
            )
          case (_, Left(err)) =>
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
          case (_, Left(err)) =>
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
          case (_, Left(err)) =>
            // membersVar is untouched, so the `controlled` select below snaps back to the
            // role that is actually stored rather than showing the failed selection.
            errorVar.set(Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderForbidden(): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span("Only group admins can manage members."))
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
            thead(tr(th("Email"), th("Role"), th(""))),
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
          "Remove",
          onClick.mapToUnit --> Observer[Unit](_ => removeMemberBus.emit(userId)),
        )
      ),
    )
  }

  private def renderRoleSelect(userId: Long, memberSignal: Signal[GroupMember]): HtmlElement = {
    select(
      cls := "select select-sm",
      GroupRole.all.map(role => option(value := role.toString, role.toString)),
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
        cls := "input flex-1",
        typ := "email",
        placeholder := "Email to invite",
        controlled(value <-- inviteEmailSignal, onInput.mapToValue --> inviteEmailVar.writer),
      ),
      select(
        cls := "select",
        GroupRole.all.map(role => option(value := role.toString, role.toString)),
        controlled(
          value <-- inviteRoleSignal.map(_.toString).distinct,
          onChange.mapToValue -->
            Observer[String] { roleStr =>
              GroupRole.all.find(_.toString == roleStr).foreach(inviteRoleVar.set)
            },
        ),
      ),
      button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, "Invite"),
    )
  }

  private def validateInvite(): Either[String, InviteMemberRequest] = {
    Validation.validateEmail(inviteEmailVar.now()).map(email => InviteMemberRequest(email, inviteRoleVar.now()))
  }

  // The key travels with the response so the observer can patch the right row without
  // reaching back into a Var mid-stream.
  private def removeMember(userId: Long): EventStream[(Long, Either[ApiError, Unit])] = {
    ApiClient.delete(s"/api/groups/$groupId/members/$userId").map(result => (userId, result))
  }

  private def changeRole(userIdAndRole: (Long, GroupRole)): EventStream[((Long, GroupRole), Either[ApiError, Unit])] = {
    val (userId, role) = userIdAndRole
    ApiClient
      .putBodyNoContent(s"/api/groups/$groupId/members/$userId", UpdateRoleRequest(role))
      .map(result => (userIdAndRole, result))
  }

  private def renderAlert(kind: String, message: String): HtmlElement = {
    div(role := "alert", cls := s"alert $kind mb-4", span(message))
  }
}
