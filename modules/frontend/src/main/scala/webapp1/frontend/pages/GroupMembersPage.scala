package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.{AppShell, GroupSubmenu}
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.{Group, GroupMember, GroupRole, User}
import webapp1.shared.dto.{InviteMemberRequest, UpdateRoleRequest}

object GroupMembersPage {
  def render(user: User, groupId: Long): HtmlElement =
    AppShell.render(user, Page.GroupMembers(groupId), new GroupMembersPage(groupId).render())
}

/** Group admin-only member management, split out of [[GroupDetailPage]] into its own
  * route/view (linked from [[GroupSubmenu]]) so it's not cluttering the group overview
  * for non-admin members. Group-admin status is only known once the group itself has
  * loaded, so the access gate below is evaluated post-load rather than via [[Page.guardFor]].
  */
private class GroupMembersPage(groupId: Long) {
  private val groupVar   = Var(Option.empty[Group])
  private val membersVar = Var(List.empty[GroupMember])

  private val inviteEmailVar = Var("")
  private val inviteRoleVar  = Var[GroupRole](GroupRole.ReadWrite)

  private val errorVar: Var[Option[String]] = Var(None)
  private val infoVar: Var[Option[String]]  = Var(None)

  private val loadBus         = new EventBus[Unit]()
  private val inviteBus       = new EventBus[Unit]()
  private val removeMemberBus = new EventBus[Long]()
  private val roleChangeBus   = new EventBus[(Long, GroupRole)]()

  def render(): HtmlElement = {
    div(
      div(cls := "mb-4", a(cls := "link", AppRouter.router.navigateTo(Page.GroupDetail(groupId)), "← Back to group")),
      h1(
        cls := "text-2xl font-bold mb-4",
        text <-- groupVar.signal.map(_.map(g => s"${g.name} — Members").getOrElse("Members")),
      ),
      child.maybe <-- groupVar.signal.map(_.map(g => GroupSubmenu.render(groupId, Page.GroupMembers(groupId), g.myRole))),
      child.maybe <-- errorVar.signal.map(_.map(msg => renderAlert("alert-error", msg))),
      child.maybe <-- infoVar.signal.map(_.map(msg => renderAlert("alert-info", msg))),
      child.maybe <-- groupVar.signal.map {
        case Some(g) if g.myRole.isAdmin => Some(renderMembersSection())
        case Some(_)                     => Some(renderForbidden())
        case None                        => None
      },
      loadBus.events.flatMapSwitch(_ => ApiClient.get[Group](s"/api/groups/$groupId")) --> Observer[Either[ApiError, Group]] {
        case Right(g)  => groupVar.set(Some(g))
        case Left(err) => errorVar.set(Some(err.message))
      },
      loadBus.events.flatMapSwitch(_ => ApiClient.get[List[GroupMember]](s"/api/groups/$groupId/members")) --> Observer[
        Either[ApiError, List[GroupMember]]
      ] {
        case Right(items) => membersVar.set(items)
        case Left(err)    => errorVar.set(Some(err.message))
      },
      inviteBus.events.flatMapSwitch(_ => invite()) --> Observer[Unit](_ => ()),
      removeMemberBus.events.flatMapSwitch(removeMember) --> Observer[Unit](_ => ()),
      roleChangeBus.events.flatMapSwitch(changeRole) --> Observer[Unit](_ => ()),
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
            tbody(children <-- membersVar.signal.splitSeq(_.userId) { memberSignal => renderMemberRow(memberSignal) }),
          ),
        ),
        renderInviteForm(),
      ),
    )
  }

  private def renderMemberRow(memberSignal: StrictSignal[GroupMember]): HtmlElement = {
    val userId = memberSignal.now().userId
    tr(
      td(text <-- memberSignal.map(_.email)),
      td(renderRoleSelect(userId, memberSignal)),
      td(
        button(
          cls := "btn btn-ghost btn-xs",
          typ := "button",
          "Remove",
          onClick.mapToUnit --> Observer[Unit](_ => removeMemberBus.emit(userId)),
        ),
      ),
    )
  }

  private def renderRoleSelect(userId: Long, memberSignal: Signal[GroupMember]): HtmlElement = {
    select(
      cls := "select select-sm",
      value <-- memberSignal.map(_.role.toString),
      onChange.mapToValue --> Observer[String] { roleStr =>
        GroupRole.all.find(_.toString == roleStr).foreach(role => roleChangeBus.emit((userId, role)))
      },
      GroupRole.all.map(role => option(value := role.toString, role.toString)),
    )
  }

  private def renderInviteForm(): HtmlElement = {
    div(
      cls := "flex gap-2 mt-4",
      input(
        cls := "input flex-1",
        placeholder := "Email to invite",
        value <-- inviteEmailVar.signal,
        onInput.mapToValue --> inviteEmailVar.writer,
      ),
      select(
        cls := "select",
        value <-- inviteRoleVar.signal.map(_.toString),
        onChange.mapToValue --> Observer[String] { roleStr =>
          GroupRole.all.find(_.toString == roleStr).foreach(inviteRoleVar.set)
        },
        GroupRole.all.map(role => option(value := role.toString, role.toString)),
      ),
      button(cls := "btn btn-primary", typ := "button", "Invite", onClick.mapToUnit --> inviteBus.writer),
    )
  }

  private def invite() = {
    val email = inviteEmailVar.now()
    if (email.trim.isEmpty) {
      EventStream.fromValue((), emitOnce = true)
    } else {
      val role = inviteRoleVar.now()
      ApiClient.postBodyNoContent(s"/api/groups/$groupId/invitations", InviteMemberRequest(email, role)).map {
        case Right(_) =>
          inviteEmailVar.set("")
          errorVar.set(None)
          infoVar.set(Some(s"Invited $email"))
        case Left(err) => errorVar.set(Some(err.message))
      }
    }
  }

  private def removeMember(userId: Long) = {
    ApiClient.delete(s"/api/groups/$groupId/members/$userId").map {
      case Right(_)  => membersVar.update(_.filterNot(_.userId == userId))
      case Left(err) => errorVar.set(Some(err.message))
    }
  }

  private def changeRole(userIdAndRole: (Long, GroupRole)) = {
    val (userId, role) = userIdAndRole
    ApiClient.putBodyNoContent(s"/api/groups/$groupId/members/$userId", UpdateRoleRequest(role)).map {
      case Right(_)  => membersVar.update(_.map(m => if (m.userId == userId) m.copy(role = role) else m))
      case Left(err) => errorVar.set(Some(err.message))
    }
  }

  private def renderAlert(kind: String, message: String): HtmlElement = {
    div(role := "alert", cls := s"alert $kind mb-4", span(message))
  }
}
