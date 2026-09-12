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
      svg.circle(svg.cx := "12", svg.cy := "12", svg.r := "9"),
      svg.path(svg.d    := "M9.5 9a2.5 2.5 0 0 1 4.6-1.4c.6.9.5 2.1-.3 2.9-.4.4-.9.6-1.3.9-.4.3-.6.7-.6 1.2v.4"),
      svg.path(svg.d    := "M12 16.3h.01"),
    )
  }
}
