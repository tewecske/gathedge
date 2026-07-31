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
  def render(user: Option[User], token: String): HtmlElement = {
    val content = new AcceptInvitePage(token).render()
    user match {
      case Some(u) => AppShell.render(u, Page.AcceptInvite(token), content)
      case None    => content
    }
  }
}

private class AcceptInvitePage(token: String) {
  private val infoVar: Var[Option[InvitationInfo]] = Var(None)
  private val errorVar: Var[Option[String]]        = Var(None)

  private val loadBus   = new EventBus[Unit]()
  private val acceptBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      onMountCallback(_ => loadBus.emit(())),
      cls := "min-h-screen flex items-center justify-center bg-base-200 p-4",
      div(
        cls := "card w-full max-w-md bg-base-100 shadow-xl",
        div(
          cls := "card-body",
          h1(cls := "card-title", "Group invitation"),
          child.maybe <-- errorVar.signal.map(_.map(renderError)),
          child.maybe <-- infoVar.signal.map(_.map(renderInfo)),
        ),
      ),
      loadBus.events.flatMapSwitch(_ => ApiClient.get[InvitationInfo](s"/api/invitations/$token")) --> Observer[
        Either[ApiError, InvitationInfo]
      ] {
        case Right(info) => infoVar.set(Some(info))
        case Left(err)   => errorVar.set(Some(err.message))
      },
      acceptBus.events.flatMapSwitch(_ => accept()) --> Observer[Unit](_ => ()),
    )
  }

  private def renderInfo(info: InvitationInfo): HtmlElement = {
    val currentUser = AppState.currentUserVar.now()
    div(
      p("You've been invited to join ", strong(info.groupName), s" as ${info.role}."),
      p(cls := "text-sm opacity-70 mt-1", s"Invitation sent to ${info.email}."),
      if (info.accepted) {
        p(cls := "mt-4", "This invitation has already been accepted.")
      } else if (info.expired) {
        p(cls := "mt-4", "This invitation has expired.")
      } else {
        currentUser match {
          case None =>
            div(
              cls := "mt-4 flex gap-2",
              a(cls := "btn btn-primary", AppRouter.router.navigateTo(Page.SignIn), "Sign in"),
              a(cls := "btn", AppRouter.router.navigateTo(Page.SignUp), "Sign up"),
            )
          case Some(u) if u.email.equalsIgnoreCase(info.email) =>
            button(
              cls := "btn btn-primary mt-4",
              typ := "button",
              "Accept invitation",
              onClick.mapToUnit --> acceptBus.writer,
            )
          case Some(u) =>
            p(cls := "mt-4 text-warning", s"This invitation was sent to ${info.email}, but you're signed in as ${u.email}.")
        }
      },
    )
  }

  private def accept() = {
    ApiClient.postNoBody[Group](s"/api/invitations/$token/accept").map {
      case Right(group) =>
        errorVar.set(None)
        AppRouter.router.pushState(Page.GroupDetail(group.id))
      case Left(err) => errorVar.set(Some(err.message))
    }
  }

  private def renderError(message: String): HtmlElement = {
    div(role := "alert", cls := "alert alert-error", span(message))
  }
}
