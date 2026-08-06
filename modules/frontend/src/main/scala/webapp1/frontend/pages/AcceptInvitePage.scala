package webapp1.frontend.pages

import com.raquo.laminar.api.L._
import webapp1.frontend.api.{ApiClient, ApiError}
import webapp1.frontend.components.AppShell
import webapp1.frontend.state.AppState
import webapp1.frontend.{AppRouter, Page}
import webapp1.shared.domain.{Group, InvitationInfo, User}

object AcceptInvitePage {
  // Reachable both logged-in and logged-out (Page.AuthGuard.Public) — wrap in the
  // authenticated shell only when there's a user to show it for.
  def render(signedIn: Boolean, token: String): HtmlElement = {
    val content = new AcceptInvitePage(token).render()
    if (signedIn)
      AppShell.render(Page.AcceptInvite(token), content)
    else
      content
  }
}

private class AcceptInvitePage(token: String) {
  private val invitationVar: Var[Option[InvitationInfo]] = Var(None)
  private val invitationSignal                           = invitationVar.signal
  private val errorVar: Var[Option[String]]              = Var(None)
  private val errorSignal                                = errorVar.signal
  private val inFlightVar                                = Var(false)
  private val inFlightSignal                             = inFlightVar.signal

  private val loadBus   = new EventBus[Unit]()
  private val acceptBus = new EventBus[Unit]()

  private val acceptStream = acceptBus.events.filterWith(inFlightSignal.not)

  def render(): HtmlElement = {
    div(
      cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
      div(
        cls := "card w-full max-w-md bg-base-100 shadow-xl",
        div(
          cls := "card-body",
          h1(cls := "card-title", "Group invitation"),
          child.maybe <-- errorSignal.map(_.map(renderError)),
          // Which call-to-action to show depends on both the invitation and the signed-in user,
          // so the user is a real dependency of this signal rather than a `now()` read.
          child.maybe <--
            invitationSignal.combineWithFn(AppState.currentUserSignal)((invitation, user) =>
              invitation.map(renderInvitation(_, user))
            ),
        ),
      ),
      loadBus.events.flatMapSwitch(_ => ApiClient.getInvitation(token)) -->
        Observer[Either[ApiError, InvitationInfo]] {
          case Right(invitation) =>
            Var.set(invitationVar -> Some(invitation), errorVar -> None)
          case Left(err)         =>
            errorVar.set(Some(err.message))
        },
      acceptStream --> Observer[Unit](_ => Var.set(inFlightVar -> true, errorVar -> None)),
      acceptStream.flatMapSwitch(_ => ApiClient.acceptInvitation(token)) -->
        Observer[Either[ApiError, Group]] {
          case Right(group) =>
            inFlightVar.set(false)
            AppRouter.router.pushState(Page.GroupDetail(group.id))
          case Left(err)    =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderInvitation(invitation: InvitationInfo, currentUser: Option[User]): HtmlElement = {
    div(
      p("You've been invited to join ", strong(invitation.groupName), s" as ${invitation.role}."),
      p(cls := "text-sm opacity-70 mt-1", s"Invitation sent to ${invitation.email}."),
      if (invitation.accepted) {
        p(cls := "mt-4", "This invitation has already been accepted.")
      } else if (invitation.expired) {
        p(cls := "mt-4", "This invitation has expired.")
      } else {
        currentUser match {
          case None                                                  =>
            div(
              cls := "mt-4 flex gap-2",
              a(cls := "btn btn-primary", AppRouter.router.navigateTo(Page.SignIn), "Sign in"),
              a(cls := "btn", AppRouter.router.navigateTo(Page.SignUp), "Sign up"),
            )
          case Some(u) if u.email.equalsIgnoreCase(invitation.email) =>
            button(
              cls := "btn btn-primary mt-4",
              typ := "button",
              disabled <-- inFlightSignal,
              "Accept invitation",
              onClick.mapToUnit --> acceptBus.writer,
            )
          case Some(u)                                               =>
            p(
              cls := "mt-4 text-warning",
              s"This invitation was sent to ${invitation.email}, but you're signed in as ${u.email}.",
            )
        }
      },
    )
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
