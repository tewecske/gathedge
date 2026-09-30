package gathedge.frontend.components

import com.raquo.laminar.api.L._

/** A small "?" mark that reveals a sentence or two of explanation on hover, focus or tap, next to whatever it explains.
  *
  * The text goes in the shared [[Tooltip]], which wraps a paragraph and keeps it on screen. The trigger's `aria-label`
  * carries the same text, since the tooltip says nothing to a screen reader.
  */
object HelpIcon {

  def render(text: String): HtmlElement = {
    button(
      typ        := "button",
      cls        := "btn btn-ghost btn-circle btn-xs align-middle",
      aria.label := text,
      Tooltip(text, Tooltip.Placement.Bottom),
      questionMark(),
    )
  }

  /** A question-mark-in-a-circle, drawn rather than typed — see `WordCollect.warningMark`'s doc comment for why an
    * SVG's box, not a character's line box, is what keeps a mark this small centred beside text of any size.
    */
  private def questionMark(): SvgElement = {
    svg.svg(
      svg.cls            := "h-4 w-4 opacity-60",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "1.5",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.path(
        svg.d :=
          "M9.879 7.519c1.171-1.025 3.071-1.025 4.242 0 1.172 1.025 1.172 2.687 0 3.712-.203.179-.43.326-.67.442-.745.361-1.45.999-1.45 1.827v.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 5.25h.008v.008H12v-.008Z"
      ),
    )
  }
}
