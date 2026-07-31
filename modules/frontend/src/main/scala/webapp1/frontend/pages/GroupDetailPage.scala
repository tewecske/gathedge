package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.AppShell
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.{Group, GroupMember, GroupPair, GroupRole, User}
import webapp1.shared.dto.{CreatePairRequest, InviteMemberRequest, UpdateRoleRequest}

object GroupDetailPage {
  def render(user: User, groupId: Long): HtmlElement =
    AppShell.render(user, Page.GroupDetail(groupId), new GroupDetailPage(groupId).render())
}

private class GroupDetailPage(groupId: Long) {
  private val groupVar   = Var(Option.empty[Group])
  private val pairsVar   = Var(List.empty[GroupPair])
  private val membersVar = Var(List.empty[GroupMember])

  private val sourceVar      = Var("")
  private val targetVar      = Var("")
  private val inviteEmailVar = Var("")
  private val inviteRoleVar  = Var[GroupRole](GroupRole.ReadWrite)

  private val errorVar: Var[Option[String]] = Var(None)
  private val infoVar: Var[Option[String]]  = Var(None)

  private val loadBus         = new EventBus[Unit]()
  private val addPairBus      = new EventBus[Unit]()
  private val inviteBus       = new EventBus[Unit]()
  private val removeMemberBus = new EventBus[Long]()
  private val roleChangeBus   = new EventBus[(Long, GroupRole)]()
  private val deleteGroupBus  = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      div(cls := "mb-4", a(cls := "link", AppRouter.router.navigateTo(Page.Groups), "← Back to groups")),
      h1(cls := "text-2xl font-bold mb-4", text <-- groupVar.signal.map(_.map(_.name).getOrElse("Group"))),
      child.maybe <-- errorVar.signal.map(_.map(msg => renderAlert("alert-error", msg))),
      child.maybe <-- infoVar.signal.map(_.map(msg => renderAlert("alert-info", msg))),
      child.maybe <-- groupVar.signal.map(_.filter(_.myRole.canWrite).map(_ => renderAddPairForm())),
      renderPairsTable(),
      renderMembersSection(),
      child.maybe <-- groupVar.signal.map(_.filter(_.myRole.isAdmin).map(_ => renderDeleteGroupButton())),
      loadBus.events.flatMapSwitch(_ => ApiClient.get[Group](s"/api/groups/$groupId")) --> Observer[Either[ApiError, Group]] {
        case Right(g)  => groupVar.set(Some(g))
        case Left(err) => errorVar.set(Some(err.message))
      },
      loadBus.events.flatMapSwitch(_ => ApiClient.get[List[GroupPair]](s"/api/groups/$groupId/pairs")) --> Observer[
        Either[ApiError, List[GroupPair]]
      ] {
        case Right(items) => pairsVar.set(items)
        case Left(err)    => errorVar.set(Some(err.message))
      },
      loadBus.events.flatMapSwitch(_ => ApiClient.get[List[GroupMember]](s"/api/groups/$groupId/members")) --> Observer[
        Either[ApiError, List[GroupMember]]
      ] {
        case Right(items) => membersVar.set(items)
        case Left(err)    => errorVar.set(Some(err.message))
      },
      addPairBus.events.flatMapSwitch(_ => addPair()) --> Observer[Unit](_ => ()),
      inviteBus.events.flatMapSwitch(_ => invite()) --> Observer[Unit](_ => ()),
      removeMemberBus.events.flatMapSwitch(removeMember) --> Observer[Unit](_ => ()),
      roleChangeBus.events.flatMapSwitch(changeRole) --> Observer[Unit](_ => ()),
      deleteGroupBus.events.flatMapSwitch(_ => deleteGroup()) --> Observer[Unit](_ => ()),
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderAddPairForm(): HtmlElement = {
    div(
      cls := "flex gap-2 mb-4",
      input(
        cls := "input flex-1",
        placeholder := "Source",
        value <-- sourceVar.signal,
        onInput.mapToValue --> sourceVar.writer,
      ),
      input(
        cls := "input flex-1",
        placeholder := "Target",
        value <-- targetVar.signal,
        onInput.mapToValue --> targetVar.writer,
      ),
      button(cls := "btn btn-primary", typ := "button", "Add", onClick.mapToUnit --> addPairBus.writer),
    )
  }

  private def renderPairsTable(): HtmlElement = {
    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table",
        thead(tr(th("Source"), th("Target"), th("Added by"))),
        tbody(children <-- pairsVar.signal.splitSeq(_.id) { pairSignal => renderPairRow(pairSignal) }),
      ),
    )
  }

  private def renderPairRow(pairSignal: Signal[GroupPair]): HtmlElement = {
    tr(
      td(text <-- pairSignal.map(_.source)),
      td(text <-- pairSignal.map(_.target)),
      td(text <-- pairSignal.map(_.createdByEmail)),
    )
  }

  private def renderMembersSection(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mt-4",
      div(
        cls := "card-body",
        h2(cls := "card-title", "Members"),
        div(
          cls := "overflow-x-auto",
          table(
            cls := "table",
            thead(tr(th("Email"), th("Role"), th(""))),
            tbody(children <-- membersVar.signal.splitSeq(_.userId) { memberSignal => renderMemberRow(memberSignal) }),
          ),
        ),
        child.maybe <-- groupVar.signal.map(_.filter(_.myRole.isAdmin).map(_ => renderInviteForm())),
      ),
    )
  }

  private def renderMemberRow(memberSignal: StrictSignal[GroupMember]): HtmlElement = {
    val userId = memberSignal.now().userId
    val isAdminGroup = groupVar.signal.map(_.exists(_.myRole.isAdmin))
    tr(
      td(text <-- memberSignal.map(_.email)),
      td(
        child <-- isAdminGroup.map { isAdmin =>
          if (isAdmin) renderRoleSelect(userId, memberSignal) else span(text <-- memberSignal.map(_.role.toString))
        },
      ),
      td(
        child.maybe <-- isAdminGroup.map {
          Option.when(_) {
            button(
              cls := "btn btn-ghost btn-xs",
              typ := "button",
              "Remove",
              onClick.mapToUnit --> Observer[Unit](_ => removeMemberBus.emit(userId)),
            )
          }
        },
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

  private def renderDeleteGroupButton(): HtmlElement = {
    div(
      cls := "mt-4",
      button(
        cls := "btn btn-error btn-outline btn-sm",
        typ := "button",
        "Delete group",
        onClick.mapToUnit --> Observer[Unit] { _ =>
          if (dom.window.confirm("Delete this group? This cannot be undone.")) deleteGroupBus.emit(())
        },
      ),
    )
  }

  private def addPair() = {
    val source = sourceVar.now()
    val target = targetVar.now()
    if (source.trim.isEmpty || target.trim.isEmpty) {
      EventStream.fromValue((), emitOnce = true)
    } else {
      ApiClient.post[CreatePairRequest, GroupPair](s"/api/groups/$groupId/pairs", CreatePairRequest(source, target)).map {
        case Right(pair) =>
          pairsVar.update(_ :+ pair)
          sourceVar.set("")
          targetVar.set("")
          errorVar.set(None)
        case Left(err) => errorVar.set(Some(err.message))
      }
    }
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

  private def deleteGroup() = {
    ApiClient.delete(s"/api/groups/$groupId").map {
      case Right(_)  => AppRouter.router.pushState(Page.Groups)
      case Left(err) => errorVar.set(Some(err.message))
    }
  }

  private def renderAlert(kind: String, message: String): HtmlElement = {
    div(role := "alert", cls := s"alert $kind mb-4", span(message))
  }
}
