package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, ProgressShareApiClient}
import gathedge.frontend.components.{Alert, AppShell}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.SharedWithMe
import gathedge.shared.i18n.UiKeys

/** The viewer side of progress sharing: redeeming somebody else's share code, and the list of accounts already sharing
  * their game history with the caller. The sharer side (minting/revoking a code) lives on `SettingsPage`.
  */
object SharedProgressPage {

  def render(): HtmlElement = {
    AppShell.render(Page.SharedProgress, new SharedProgressPage().render())
  }
}

private class SharedProgressPage {

  private val sharersVar: Var[List[SharedWithMe]] = Var(Nil)

  private val codeInputVar = Var("")

  private val errorVar: Var[Option[String]]  = Var(None)
  private val noticeVar: Var[Option[String]] = Var(None)
  private val inFlightVar                    = Var(false)

  private val reloadBus = new EventBus[Unit]()
  private val redeemBus = new EventBus[Unit]()

  private val redeemStream = redeemBus.events.filterWith(inFlightVar.signal.not).sample(codeInputVar.signal)

  def render(): HtmlElement = {
    div(
      cls := "p-4 flex flex-col gap-6",
      h1(cls := "text-2xl font-bold", I18n.t(UiKeys.sharedProgressTitle)),
      Alert.maybeError(errorVar.signal),
      Alert.maybeInfo(noticeVar.signal),
      renderRedeemForm(),
      renderList(),
      reloadBus.events.flatMapSwitch(_ => ProgressShareApiClient.sharedWithMe()) -->
        Observer[Either[ApiError, List[SharedWithMe]]] {
          case Right(list) =>
            sharersVar.set(list)
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
      redeemStream -->
        Observer[String](_ => Var.set(inFlightVar -> true, errorVar -> None, noticeVar -> None)),
      redeemStream.flatMapSwitch(code => ProgressShareApiClient.redeem(code)) -->
        Observer[Either[ApiError, Unit]] {
          case Right(_)  =>
            Var.set(
              inFlightVar  -> false,
              codeInputVar -> "",
              noticeVar    -> Some(I18n.t(UiKeys.sharedProgressRedeemSuccess)),
            )
            reloadBus.emit(())
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def renderRedeemForm(): HtmlElement = {
    form(
      cls := "card bg-base-100 shadow",
      onSubmit.preventDefault.mapToUnit --> redeemBus.writer,
      div(
        cls := "card-body",
        fieldSet(
          cls := "fieldset",
          label(cls := "fieldset-legend", I18n.t(UiKeys.sharedProgressRedeemLabel)),
          div(
            cls     := "flex gap-2",
            input(
              cls         := "input flex-1",
              placeholder := I18n.t(UiKeys.sharedProgressRedeemPlaceholder),
              controlled(value <-- codeInputVar.signal, onInput.mapToValue --> codeInputVar.writer),
            ),
            button(
              cls         := "btn btn-primary",
              typ         := "submit",
              disabled <-- inFlightVar.signal,
              I18n.t(UiKeys.sharedProgressRedeemButton),
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
        h2(cls := "card-title text-lg", I18n.t(UiKeys.sharedProgressListTitle)),
        child.maybe <--
          sharersVar.signal.map(list =>
            Option.when(list.isEmpty)(p(cls := "text-sm opacity-70", I18n.t(UiKeys.sharedProgressListEmpty)))
          ),
        ul(
          cls  := "flex flex-col divide-y divide-base-300",
          children <-- sharersVar.signal.map(_.map(renderRow)),
        ),
      ),
    )
  }

  private def renderRow(sharer: SharedWithMe): HtmlElement = {
    li(
      cls := "flex items-center justify-between gap-4 py-3",
      span(sharer.email.getOrElse(I18n.t(UiKeys.sharedProgressGuestBadge))),
      a(
        cls := "btn btn-sm",
        AppRouter.router.navigateTo(Page.SharedPlayerHistory(sharer.sharerUserId)),
        I18n.t(UiKeys.sharedProgressViewButton),
      ),
    )
  }
}
