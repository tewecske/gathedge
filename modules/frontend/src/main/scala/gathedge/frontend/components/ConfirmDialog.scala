package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.I18n
import gathedge.shared.i18n.UiKeys

/** One daisyUI confirm modal that a page shares between all its confirmations, in place of `dom.window.confirm`.
  *
  * A page makes one instance, puts [[render]] once in its tree, and calls [[ask]] from each button that needs a
  * confirmation. The modal shows the label as its title, the message, Cancel, and the confirm button. The action runs
  * only when the confirm button is pressed. Cancel and the backdrop close the modal and do nothing else.
  *
  * The confirm button is `btn-error` when `danger` is set: a delete or a remove. A warning before a change that is not
  * a delete (a new invite code) sets `danger` to `false` and gets `btn-primary`.
  *
  * The confirm button has the same label as the page button that asked. An e2e test finds the modal's one through
  * `.modal-open .modal-box`.
  */
final class ConfirmDialog {

  private val requestVar: Var[Option[ConfirmDialog.Request]] = Var(None)

  /** Opens the modal. `label` is the title and the confirm button's text. It is the label of the button that asked, so
    * the reader sees the same words on both. `onConfirm` runs when the reader presses the confirm button, and at no
    * other time.
    */
  def ask(label: String, message: String, danger: Boolean)(onConfirm: => Unit): Unit = {
    requestVar.set(Some(ConfirmDialog.Request(label, message, danger, () => onConfirm)))
  }

  private def close(): Unit = requestVar.set(None)

  def render(): HtmlElement = {
    div(
      cls  := "modal",
      cls("modal-open") <-- requestVar.signal.map(_.isDefined),
      role := "dialog",
      child.maybe <-- requestVar.signal.map(_.map(renderBox)),
      div(cls := "modal-backdrop", onClick.mapToUnit --> Observer[Unit](_ => close())),
    )
  }

  private def renderBox(request: ConfirmDialog.Request): HtmlElement = {
    div(
      cls := "modal-box w-full max-w-sm",
      h3(cls := "font-bold text-lg", request.label),
      p(cls  := "py-4", request.message),
      div(
        cls  := "modal-action",
        button(
          cls := "btn btn-sm",
          typ := "button",
          I18n.t(UiKeys.commonCancel),
          onClick.mapToUnit --> Observer[Unit](_ => close()),
        ),
        button(
          cls := (if (request.danger) "btn btn-sm btn-error" else "btn btn-sm btn-primary"),
          typ := "button",
          request.label,
          onClick.mapToUnit --> Observer[Unit] { _ =>
            close()
            request.onConfirm()
          },
        ),
      ),
    )
  }
}

object ConfirmDialog {

  private final case class Request(
    label: String,
    message: String,
    danger: Boolean,
    onConfirm: () => Unit,
  )
}
