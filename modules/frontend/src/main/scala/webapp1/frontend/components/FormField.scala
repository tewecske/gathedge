package webapp1.frontend.components

import com.raquo.laminar.api.L._

/** One form row: the caller's controls, plus that field's error message underneath.
  *
  * The signal is expected to be already gated on the form's `showErrors` flag (see the form state case classes in the
  * `pages` package), so nothing appears before the first submit attempt. Extra layout classes can be passed in as mods
  * — Laminar's `cls` accumulates rather than replacing.
  */
object FormField {
  def render(errorSignal: Signal[Option[String]])(mods: Modifier[HtmlElement]*): HtmlElement = {
    div(
      cls := "flex flex-col gap-1",
      mods,
      child.maybe <--
        errorSignal.map(
          _.map { message =>
            span(cls := "label-text-alt text-error", message)
          }
        ),
    )
  }
}
