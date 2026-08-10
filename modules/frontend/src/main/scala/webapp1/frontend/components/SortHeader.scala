package webapp1.frontend.components

import com.raquo.laminar.api.L._
import webapp1.shared.dto.SortDirection

/** A column heading that can be clicked to order the table by it.
  *
  * Three states, cycling: unordered, ascending, descending, and back to unordered. The third state is the one that is
  * easy to leave out and the one that matters — without it there is no way back to the listing's own order, which for
  * the audit trail is "most recent first" and is what an administrator opens the page to see.
  *
  * The sort is a *request*, not a local view: both callers page server-side, so a click here changes the query and the
  * next response is a different set of rows, not the same rows rearranged. That is why the state is held by the page
  * and passed in — this renders a `th` and reports what was asked for, and decides nothing.
  */
object SortHeader {

  /** Which column a listing is ordered by, if any, and which way. */
  final case class Sort(column: Option[String], descending: Boolean) {

    /** `Some(descending)` when this sort is on `candidate`, `None` when some other column (or none) holds it. */
    def directionOf(candidate: String): Option[Boolean] = {
      Option.when(column.contains(candidate))(descending)
    }

    /** The `dir` query parameter, or `None` when there is no column to apply it to. */
    def wire: Option[String] = Option.when(column.isDefined)(SortDirection.of(descending))
  }

  object Sort {
    val unsorted: Sort = Sort(None, descending = false)

    def ascending(column: String): Sort = Sort(Some(column), descending = false)

    def descending(column: String): Sort = Sort(Some(column), descending = true)
  }

  /** Where a click on `column` takes the listing: onto it ascending, then descending, then off it entirely. Clicking a
    * different column always starts that column ascending rather than carrying the previous direction over.
    */
  def next(current: Sort, column: String): Sort = {
    current.column match {
      case Some(`column`) if !current.descending =>
        Sort.descending(column)
      case Some(`column`)                        =>
        Sort.unsorted
      case _                                     =>
        Sort.ascending(column)
    }
  }

  /** What the glyph says: which way this column is ordered, or that it can be. */
  private def glyph(direction: Option[Boolean]): String = {
    direction match {
      case Some(true)  =>
        "▼"
      case Some(false) =>
        "▲"
      case None        =>
        "↕"
    }
  }

  /** `aria-sort` is the standard way to say which column a table is ordered by; the glyph says the same thing to a
    * reader who can see it, and neither is the only signal.
    */
  private def ariaSort(direction: Option[Boolean]): String = {
    direction match {
      case Some(true)  =>
        "descending"
      case Some(false) =>
        "ascending"
      case None        =>
        "none"
    }
  }

  /** @param label
    *   the column's heading, already worded — the caller looks it up, since several of these reuse a `MessageKeys`
    *   field label rather than minting their own.
    * @param column
    *   the wire name out of `dto.UserSort`/`dto.AuditSort` that the request carries.
    */
  def render(label: String, column: String, sort: Signal[Sort], onSort: Observer[Sort]): HtmlElement = {
    val direction = sort.map(_.directionOf(column)).distinct

    th(
      aria.sort <-- direction.map(ariaSort),
      button(
        // A button rather than a click handler on the `th`, so the column is reachable by keyboard like every other
        // control on these pages.
        cls := "inline-flex items-center gap-1 cursor-pointer hover:underline",
        typ := "button",
        label,
        span(
          cls := "text-xs",
          // The idle glyph is dimmed so the ordered column stands out among the ones merely offering to be.
          cls("opacity-40") <-- direction.map(_.isEmpty).distinct,
          text <-- direction.map(glyph),
        ),
        onClick.compose(_.sample(sort).map(next(_, column))) --> onSort,
      ),
    )
  }
}
