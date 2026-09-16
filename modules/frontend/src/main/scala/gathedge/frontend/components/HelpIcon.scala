package gathedge.frontend.components

import com.raquo.laminar.api.L._

/** A small "?" mark that reveals a sentence or two of explanation on hover, next to whatever it explains.
  *
  * Built on daisyUI's *classic* dropdown (`dropdown dropdown-hover`), not the popover-API one [[Popover]] and
  * [[LanguagePicker]] share: those open on a click, through `popovertarget`, which the Popover API has no CSS
  * equivalent for. A pure `:hover`/`:focus-within` reveal needs the older `tabindex`-based dropdown instead, so this is
  * its own small component rather than a third caller of [[Popover]].
  *
  * `text` is real DOM text, not a `data-tip` attribute (unlike `InlineRename.iconButton`'s tooltip), so a paragraph of
  * copy can wrap instead of being squeezed onto one CSS-drawn line. The trigger's `aria-label` carries the same text,
  * since a screen reader has no reason to look at a sibling element a mouse merely hovers.
  */
object HelpIcon {

  def render(text: String): HtmlElement = {
    div(
      cls := "dropdown dropdown-hover align-middle",
      div(
        tabIndex   := 0,
        role       := "button",
        cls        := "btn btn-ghost btn-circle btn-xs",
        aria.label := text,
        questionMark(),
      ),
      div(
        tabIndex   := 0,
        cls        := "dropdown-content z-20 w-64 rounded-box bg-base-100 p-3 text-sm font-normal shadow-md",
        text,
      ),
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
