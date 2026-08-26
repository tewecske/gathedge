package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, GroupApiClient}
import gathedge.frontend.components.{Alert, AppShell}
import gathedge.frontend.i18n.I18n
import gathedge.shared.i18n.UiKeys

/** Where a group's invite link lands (`/groups/join/{code}`) — redeems `code` for the signed-in caller on mount and
  * offers a way back to `Groups`. See `Page.GroupJoin`'s doc comment for why this page needs no guest detour, unlike
  * `GameInstancePage`'s public shared link.
  */
object GroupJoinPage {

  def render(code: String): HtmlElement = {
    AppShell.render(Page.GroupJoin(code), new GroupJoinPage(code).render())
  }
}

private class GroupJoinPage(code: String) {

  private val resultVar: Var[Option[Either[String, Unit]]] = Var(None)

  private val joinBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      cls := "p-4 max-w-md mx-auto flex flex-col gap-4",
      child <-- resultVar.signal.map {
        case None            => renderJoining()
        case Some(Right(())) => renderSuccess()
        case Some(Left(msg)) => renderError(msg)
      },
      joinBus.events.flatMapSwitch(_ => GroupApiClient.join(code)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  => resultVar.set(Some(Right(())))
          case Left(err) => resultVar.set(Some(Left(err.message)))
        },
      onMountCallback(_ => joinBus.emit(())),
    )
  }

  private def renderJoining(): HtmlElement = {
    div(
      cls := "flex items-center gap-2",
      span(cls := "loading loading-spinner"),
      span(I18n.t(UiKeys.groupJoinJoining)),
    )
  }

  private def renderSuccess(): HtmlElement = {
    div(
      Alert.success(I18n.t(UiKeys.groupJoinSuccess)),
      a(
        cls := "btn btn-primary",
        AppRouter.router.navigateTo(Page.Groups),
        I18n.t(UiKeys.groupJoinViewGroups),
      ),
    )
  }

  private def renderError(message: String): HtmlElement = {
    div(
      Alert.error(message),
      a(
        cls := "btn",
        AppRouter.router.navigateTo(Page.Groups),
        I18n.t(UiKeys.groupJoinViewGroups),
      ),
    )
  }
}
