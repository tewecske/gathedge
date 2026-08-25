package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GroupApiClient, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{GroupRole, Tag}
import gathedge.shared.dto.{GroupDetail, GroupMemberSummary, GroupTagSummary, InviteCodeResponse}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

/** One group's roster (members only), invite code (admins only), and attached tags — see `GroupsPage` for the
  * browse/create/join screen this is reached from.
  */
object GroupDetailPage {

  def render(groupId: Long): HtmlElement = {
    AppShell.render(Page.GroupDetail(groupId), new GroupDetailPage(groupId).render())
  }
}

private class GroupDetailPage(groupId: Long) {

  private val detailVar: Var[Option[GroupDetail]] = Var(None)
  private val myTagsVar                           = Var(List.empty[Tag])

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)
  private val busyVar                        = Var(false)

  private val attachSelectionVar = Var(Option.empty[Long])

  private val reloadBus       = new EventBus[Unit]()
  private val leaveBus        = new EventBus[Unit]()
  private val regenerateBus   = new EventBus[Unit]()
  private val setRoleBus      = new EventBus[(Long, GroupRole)]()
  private val removeMemberBus = new EventBus[Long]()
  private val attachClickBus  = new EventBus[Unit]()
  private val detachBus       = new EventBus[Long]()

  /** The selected tag id at the moment the attach button is clicked — `sample`d rather than read via `.now()`, the same
    * pattern `SharedProgressPage.redeemStream` uses for its text input.
    */
  private val attachStream = attachClickBus.events.sample(attachSelectionVar.signal).collect { case Some(id) => id }

  private val currentEmailSignal = AppState.currentUserSignal.map(_.flatMap(_.email))

  def render(): HtmlElement = {
    div(
      cls := "p-4 max-w-3xl flex flex-col gap-6",
      div(
        cls := "flex items-center justify-between",
        h1(cls := "text-2xl font-bold", child.text <-- detailVar.signal.map(_.map(_.name).getOrElse(""))),
        a(cls  := "btn btn-sm", AppRouter.router.navigateTo(Page.Groups), "←"),
      ),
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(noticeVar.signal),
      child.maybe <-- detailVar.signal.map(_.map(renderBody)),
      reloadBus.events.flatMapSwitch(_ => GroupApiClient.get(groupId)) -->
        Observer[Either[ApiError, GroupDetail]] {
          case Right(detail) =>
            Var.set(detailVar -> Some(detail), attachSelectionVar -> None)
          case Left(err)     =>
            errorVar.set(Some(err.message))
        },
      reloadBus.events.flatMapSwitch(_ => WordApiClient.listTags) -->
        Observer[Either[ApiError, List[Tag]]] {
          case Right(tags) =>
            myTagsVar.set(tags)
          case Left(_)     =>
            () // Only feeds the attach dropdown; a failure here does not block the rest of the page.
        },
      // A <select> auto-selects its first option without firing onChange, so the attach button must default
      // to that same first eligible tag itself — otherwise it stays disabled until the user picks a *different*
      // option, which is impossible when there's only one eligible tag.
      myTagsVar.signal.map(_.filter(tag => tag.ownedByMe && tag.group.isEmpty).headOption.map(_.id)) -->
        attachSelectionVar.writer,
      leaveBus.events --> Observer[Unit](_ => Var.set(busyVar -> true, errorVar -> None)),
      leaveBus.events.flatMapSwitch(_ => GroupApiClient.leave(groupId)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            AppRouter.router.pushState(Page.Groups)
          case Left(err) =>
            Var.set(busyVar -> false, errorVar -> Some(err.message))
        },
      regenerateBus.events --> Observer[Unit](_ => Var.set(busyVar -> true, errorVar -> None)),
      regenerateBus.events.flatMapSwitch(_ => GroupApiClient.regenerateInviteCode(groupId)) -->
        Observer[Either[ApiError, InviteCodeResponse]] {
          case Right(_)  =>
            busyVar.set(false)
            reloadBus.emit(())
          case Left(err) =>
            Var.set(busyVar -> false, errorVar -> Some(err.message))
        },
      setRoleBus.events --> Observer[(Long, GroupRole)](_ => Var.set(busyVar -> true, errorVar -> None)),
      setRoleBus.events.flatMapSwitch { case (userId, role) => GroupApiClient.setMemberRole(groupId, userId, role) } -->
        Observer[Either[ApiError, GroupMemberSummary]] {
          case Right(_)  =>
            busyVar.set(false)
            reloadBus.emit(())
          case Left(err) =>
            Var.set(busyVar -> false, errorVar -> Some(err.message))
        },
      removeMemberBus.events --> Observer[Long](_ => Var.set(busyVar -> true, errorVar -> None)),
      removeMemberBus.events.flatMapSwitch(userId => GroupApiClient.removeMember(groupId, userId)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            busyVar.set(false)
            reloadBus.emit(())
          case Left(err) =>
            Var.set(busyVar -> false, errorVar -> Some(err.message))
        },
      attachStream --> Observer[Long](_ => Var.set(busyVar -> true, errorVar -> None)),
      attachStream.flatMapSwitch(tagId => GroupApiClient.attachTag(groupId, tagId)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            busyVar.set(false)
            reloadBus.emit(())
          case Left(err) =>
            Var.set(busyVar -> false, errorVar -> Some(err.message))
        },
      detachBus.events --> Observer[Long](_ => Var.set(busyVar -> true, errorVar -> None)),
      detachBus.events.flatMapSwitch(tagId => GroupApiClient.detachTag(groupId, tagId)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            busyVar.set(false)
            reloadBus.emit(())
          case Left(err) =>
            Var.set(busyVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def renderBody(detail: GroupDetail): HtmlElement = {
    div(
      cls := "flex flex-col gap-6",
      renderRoster(detail),
      Option.when(detail.inviteCode.isDefined)(renderInviteCode(detail)),
      renderTags(detail),
    )
  }

  private def renderRoster(detail: GroupDetail): HtmlElement = {
    val isAdmin = detail.viewerRole.contains(GroupRole.Admin)
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        h2(cls := "card-title text-lg", I18n.t(UiKeys.groupDetailRosterTitle)),
        if (detail.viewerRole.isEmpty) {
          p(cls := "text-sm opacity-70", I18n.t(UiKeys.groupDetailRosterHidden))
        } else {
          div(
            cls := "flex flex-col gap-3",
            div(
              cls := "overflow-x-auto",
              table(
                cls := "table",
                tbody(detail.members.map(member => renderMemberRow(member, isAdmin))),
              ),
            ),
            div(
              button(
                cls := "btn btn-sm btn-outline",
                typ := "button",
                disabled <-- busyVar.signal,
                I18n.t(UiKeys.groupDetailLeaveButton),
                onClick.mapToUnit --> Observer[Unit] { _ =>
                  if (dom.window.confirm(I18n.t(UiKeys.groupDetailLeaveConfirm))) leaveBus.emit(())
                },
              )
            ),
          )
        },
      ),
    )
  }

  private def renderMemberRow(member: GroupMemberSummary, viewerIsAdmin: Boolean): HtmlElement = {
    val label = member.email.getOrElse(I18n.t(UiKeys.sharedProgressGuestBadge))
    tr(
      td(label),
      td(
        member.role match {
          case GroupRole.Admin  => span(cls := "badge badge-primary", I18n.t(UiKeys.groupsRoleAdmin))
          case GroupRole.Member => span(cls := "badge", I18n.t(UiKeys.groupsRoleMember))
        }
      ),
      td(
        cls := "text-right",
        if (viewerIsAdmin) {
          div(
            cls := "flex gap-2 justify-end",
            button(
              cls := "btn btn-xs",
              typ := "button",
              disabled <-- busyVar.signal,
              member.role match {
                case GroupRole.Admin  => I18n.t(UiKeys.groupDetailDemoteButton)
                case GroupRole.Member => I18n.t(UiKeys.groupDetailPromoteButton)
              },
              onClick.mapToUnit --> Observer[Unit] { _ =>
                val next = if (member.role == GroupRole.Admin) GroupRole.Member else GroupRole.Admin
                setRoleBus.emit((member.userId, next))
              },
            ),
            button(
              cls := "btn btn-xs btn-error btn-outline",
              typ := "button",
              disabled <-- busyVar.signal,
              I18n.t(UiKeys.groupDetailRemoveButton),
              onClick.mapToUnit --> Observer[Unit] { _ =>
                if (dom.window.confirm(I18n.t(UiKeys.groupDetailRemoveConfirm))) removeMemberBus.emit(member.userId)
              },
            ),
          )
        } else {
          emptyNode
        },
      ),
    )
  }

  private def renderInviteCode(detail: GroupDetail): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        h2(cls := "card-title text-lg", I18n.t(UiKeys.groupDetailInviteCodeTitle)),
        div(
          cls  := "flex items-center gap-3",
          code(cls := "text-lg select-all", detail.inviteCode.getOrElse("")),
          button(
            cls    := "btn btn-sm btn-outline",
            typ    := "button",
            disabled <-- busyVar.signal,
            I18n.t(UiKeys.groupDetailInviteCodeRegenerate),
            onClick.mapToUnit --> Observer[Unit] { _ =>
              if (dom.window.confirm(I18n.t(UiKeys.groupDetailInviteCodeRegenerateConfirm))) regenerateBus.emit(())
            },
          ),
        ),
      ),
    )
  }

  private def renderTags(detail: GroupDetail): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body",
        h2(cls := "card-title text-lg", I18n.t(UiKeys.groupDetailTagsTitle)),
        Option.when(detail.tags.isEmpty)(p(cls := "text-sm opacity-70", I18n.t(UiKeys.groupDetailTagsEmpty))),
        ul(
          cls  := "flex flex-col divide-y divide-base-300",
          detail.tags.map(tag => renderTagRow(tag, detail)),
        ),
        Option.when(detail.viewerRole.isDefined)(renderAttachControl()),
      ),
    )
  }

  private def renderTagRow(tag: GroupTagSummary, detail: GroupDetail): HtmlElement = {
    val isAdmin = detail.viewerRole.contains(GroupRole.Admin)
    li(
      cls := "flex items-center justify-between gap-4 py-3",
      a(
        cls    := "link",
        AppRouter.router.navigateTo(Page.TagDetail(tag.id)),
        s"${tag.name} (${tag.wordCount})",
      ),
      span(cls := "text-xs opacity-60", tag.ownerEmail.getOrElse(I18n.t(UiKeys.sharedProgressGuestBadge))),
      child.maybe <-- currentEmailSignal.map { email =>
        val ownsIt = email.isDefined && email == tag.ownerEmail
        Option.when(isAdmin || ownsIt)(
          button(
            cls := "btn btn-xs btn-outline",
            typ := "button",
            disabled <-- busyVar.signal,
            I18n.t(UiKeys.groupDetailDetachButton),
            onClick.mapToUnit --> Observer[Unit] { _ =>
              if (dom.window.confirm(I18n.t(UiKeys.groupDetailDetachConfirm))) detachBus.emit(tag.id)
            },
          )
        )
      },
    )
  }

  private def renderAttachControl(): HtmlElement = {
    div(
      cls := "mt-4 pt-4 border-t border-base-300",
      child.maybe <-- myTagsVar.signal.map { tags =>
        val eligible = tags.filter(tag => tag.ownedByMe && tag.group.isEmpty)
        Option.when(eligible.isEmpty)(p(cls := "text-sm opacity-70", I18n.t(UiKeys.groupDetailAttachNoneAvailable)))
      },
      child.maybe <-- myTagsVar.signal.map { tags =>
        val eligible = tags.filter(tag => tag.ownedByMe && tag.group.isEmpty)
        Option.when(eligible.nonEmpty)(
          div(
            cls := "flex items-end gap-2",
            label(
              cls := "flex flex-col gap-1",
              span(cls := "label-text text-xs", I18n.t(UiKeys.groupDetailAttachLabel)),
              select(
                cls    := "select select-sm",
                eligible.map(tag => option(value := tag.id.toString, tag.name)),
                onChange.mapToValue --> attachSelectionVar.writer.contramap[String](_.toLongOption),
              ),
            ),
            button(
              cls := "btn btn-sm btn-primary",
              typ := "button",
              disabled <-- attachSelectionVar.signal.combineWith(busyVar.signal).map { case (sel, busy) =>
                sel.isEmpty || busy
              },
              I18n.t(UiKeys.groupDetailAttachButton),
              onClick.mapToUnit --> attachClickBus.writer,
            ),
          )
        )
      },
    )
  }
}
