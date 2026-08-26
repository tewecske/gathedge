package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GroupApiClient, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, ShareRow}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{GroupRole, Tag}
import gathedge.shared.dto.{GroupDetail, GroupMemberSummary, GroupTagSummary, InviteCodeResponse}
import gathedge.shared.i18n.UiKeys
import org.scalajs.dom

import scala.concurrent.Future

/** One group's roster (members only), invite code (admins only), and attached tags — see `GroupsPage` for the
  * browse/create/join screen this is reached from.
  */
object GroupDetailPage {

  /** See [[GameInstancePage.render]]'s doc comment on why `generateQr` is threaded in rather than called directly: the
    * reason (keeping the `qrcode` npm package's `@JSImport` out of the test linker's reachable graph) applies verbatim
    * here.
    */
  def render(groupId: Long, generateQr: String => Future[String]): HtmlElement = {
    AppShell.render(Page.GroupDetail(groupId), new GroupDetailPage(groupId, generateQr).render())
  }
}

private class GroupDetailPage(groupId: Long, generateQr: String => Future[String]) {

  private val detailVar: Var[Option[GroupDetail]] = Var(None)
  private val myTagsVar                           = Var(List.empty[Tag])

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)
  private val busyVar                        = Var(false)

  private val attachSelectionVar = Var(Option.empty[Long])

  /** The invite code [[shareRow]]'s `link` closure reads — kept in its own `Var` rather than re-derived from
    * `detailVar` each time, since a regenerate has to reach [[ShareRow.resetQr]] with the *new* code already in place
    * (see the regenerate handler below), and reading `detailVar.now()` there would still see the old one.
    */
  private val inviteCodeVar = Var("")

  /** Copy-link, Web Share and QR code for `/groups/join/{code}` — see [[components.ShareRow]]. Unlike
    * `GameInstancePage`'s own, this page's URL is not the shareable one, so `link` builds `inviteLink` from
    * [[inviteCodeVar]] instead of reading `dom.window.location.href`.
    */
  private val shareRow = new ShareRow(
    () => inviteLink(inviteCodeVar.now()),
    () => detailVar.now().map(_.name).getOrElse(""),
    generateQr,
    msg => noticeVar.set(Some(msg)),
  )

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
      cls := "max-w-3xl mx-auto",
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(noticeVar.signal),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          div(
            cls := "flex items-center justify-between",
            h1(cls := "card-title text-2xl", child.text <-- detailVar.signal.map(_.map(_.name).getOrElse(""))),
            a(cls  := "btn btn-sm", AppRouter.router.navigateTo(Page.Groups), "←"),
          ),
          child.maybe <-- detailVar.signal.map(_.map(renderBody)),
        ),
      ),
      reloadBus.events.flatMapSwitch(_ => GroupApiClient.get(groupId)) -->
        Observer[Either[ApiError, GroupDetail]] {
          case Right(detail) =>
            Var.set(
              detailVar          -> Some(detail),
              attachSelectionVar -> None,
              inviteCodeVar      -> detail.inviteCode.getOrElse(""),
            )
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
          case Right(response) =>
            // The old code's QR (if any was ever generated) now encodes a dead link.
            Var.set(busyVar -> false, inviteCodeVar -> response.code)
            shareRow.resetQr()
            reloadBus.emit(())
          case Left(err)       =>
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
      cls := "flex flex-col gap-6 mt-2",
      renderRoster(detail),
      div(cls := "divider"),
      Option.when(detail.inviteCode.isDefined)(renderInviteCode(detail)),
      Option.when(detail.inviteCode.isDefined)(div(cls := "divider")),
      renderTags(detail),
    )
  }

  private def renderRoster(detail: GroupDetail): HtmlElement = {
    val isAdmin = detail.viewerRole.contains(GroupRole.Admin)
    div(
      h2(cls := "text-lg font-semibold", I18n.t(UiKeys.groupDetailRosterTitle)),
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
    val inviteCode = detail.inviteCode.getOrElse("")
    div(
      h2(cls := "text-lg font-semibold", I18n.t(UiKeys.groupDetailInviteCodeTitle)),
      div(
        cls  := "flex items-center gap-3",
        code(cls := "text-lg select-all", inviteCode),
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
      shareRow.render(),
    )
  }

  private def inviteLink(code: String): String = {
    dom.window.location.origin + AppRouter.router.relativeUrlForPage(Page.GroupJoin(code))
  }

  private def renderTags(detail: GroupDetail): HtmlElement = {
    div(
      h2(cls := "text-lg font-semibold", I18n.t(UiKeys.groupDetailTagsTitle)),
      Option.when(detail.tags.isEmpty)(p(cls := "text-sm opacity-70", I18n.t(UiKeys.groupDetailTagsEmpty))),
      ul(
        cls  := "flex flex-col divide-y divide-base-300",
        detail.tags.map(tag => renderTagRow(tag, detail)),
      ),
      Option.when(detail.viewerRole.isDefined)(renderAttachControl()),
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
