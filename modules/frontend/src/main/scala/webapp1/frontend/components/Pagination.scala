package webapp1.frontend.components

import com.raquo.laminar.api.L._
import webapp1.frontend.i18n.I18n
import webapp1.shared.dto.Paging
import webapp1.shared.i18n.UiKeys

/** The page selector under a long table: how many rows a page holds, which page is being read, and how many there are.
  *
  * Purely presentational, in the shape the laminar skill asks for — it reads the listing's state as `Signal`s and
  * writes requests back through `Observer`s, so it owns none of the state and neither caller has to model it the same
  * way. Both callers now page server-side, so the only thing this knows about the rows is how many of them exist:
  * `total` comes off the response, and every button below is arithmetic on it.
  *
  * `Paging` — the page sizes on offer, the default, the page-count arithmetic — lives in `shared` so the dropdown
  * cannot offer a size the server would clamp, and so the button count cannot disagree with the `LIMIT`/`OFFSET` that
  * produced the rows.
  */
object Pagination {

  /** How many numbered buttons the row may hold, ellipses included. Beyond this the middle is elided rather than let a
    * table with a hundred pages push its own controls off the side of the screen.
    */
  private val maxButtons = 7

  /** A page index the caller can safely use, whatever it was holding. A listing that shrinks — a filter narrowing, a
    * row deleted — leaves the stored index pointing past the end, and clamping on read means no page has to watch for
    * that and write a corrected index back.
    */
  def clampPage(page: Int, pageCount: Int): Int = {
    math.max(0, math.min(page, pageCount - 1))
  }

  /** The index of the last page, or `0` when there is nothing to show. What a caller jumps to when it has just added a
    * row at the end and wants it on screen.
    */
  def lastPage(total: Long, pageSize: Int): Int = {
    math.max(0, Paging.pageCount(total, pageSize) - 1)
  }

  /** The buttons to draw, left to right: `Some(index)` is a page, `None` an elision. The first and last page are always
    * offered, along with the current one and its neighbours; everything between collapses.
    */
  def pageItems(pageCount: Int, current: Int): List[Option[Int]] = {
    if (pageCount <= maxButtons) {
      (0 until pageCount).toList.map(Some(_))
    } else {
      val shown = {
        (List(0, pageCount - 1) ++ ((current - 1) to (current + 1)))
          .filter(index => index >= 0 && index < pageCount)
          .distinct
          .sorted
      }
      shown.foldLeft(List.empty[Option[Int]]) { (acc, index) =>
        acc.lastOption match {
          case Some(Some(previous)) if index > previous + 1 =>
            acc ++ List(None, Some(index))
          case _                                            =>
            acc :+ Some(index)
        }
      }
    }
  }

  /** @param page
    *   the current page, zero-based.
    * @param total
    *   how many rows match, across every page. The server counts it; everything on screen is derived from it.
    * @param summary
    *   what the listing calls its rows — "137 accounts", "8 entries". Supplied by the caller rather than worded here,
    *   because a count of accounts and a count of audit entries are different sentences in a language with no generic
    *   plural noun to fall back on.
    * @param busy
    *   disables the whole control while a request is out, so a second click cannot race the first.
    */
  def render(
    page: Signal[Int],
    total: Signal[Long],
    pageSize: Signal[Int],
    onPage: Observer[Int],
    onPageSize: Observer[Int],
    summary: Signal[String],
    busy: Signal[Boolean] = Val(false),
  ): HtmlElement = {
    val pageCount = total.combineWithFn(pageSize)(Paging.pageCount).distinct
    val current   = page.combineWithFn(pageCount)(clampPage).distinct

    div(
      cls := "flex flex-wrap items-center justify-between gap-4 mt-4",
      renderPageSize(pageSize, onPageSize, busy),
      renderSummary(current, pageCount, summary),
      renderPages(current, pageCount, onPage, busy),
    )
  }

  private def renderPageSize(pageSize: Signal[Int], onPageSize: Observer[Int], busy: Signal[Boolean]): HtmlElement = {
    label(
      cls := "flex items-center gap-2",
      span(cls := "label-text text-xs", I18n.t(UiKeys.commonRowsPerPage)),
      select(
        cls := "select select-sm w-auto",
        disabled <-- busy,
        Paging.pageSizes.map(size => option(value := size.toString, size.toString)),
        controlled(
          value <-- pageSize.map(_.toString).distinct,
          // A value this `select` did not offer cannot arrive, so anything unparseable is a bug rather than input.
          onChange.mapToValue.map(_.toIntOption.getOrElse(Paging.defaultPageSize)) --> onPageSize,
        ),
      ),
    )
  }

  /** The two figures the numbered buttons only imply: how many rows there are, and how many pages they fill. The page
    * indicator is one-based, unlike everything else here, because it is read rather than indexed with.
    */
  private def renderSummary(current: Signal[Int], pageCount: Signal[Int], summary: Signal[String]): HtmlElement = {
    div(
      cls := "text-sm opacity-60 flex flex-wrap items-center gap-x-2",
      span(text <-- summary),
      span(
        text <--
          current
            .combineWithFn(pageCount) { (page, count) =>
              if (count <= 0)
                ""
              else
                I18n.t(UiKeys.commonPageOf, page + 1, count)
            }
            .distinct
      ),
    )
  }

  private def renderPages(
    current: Signal[Int],
    pageCount: Signal[Int],
    onPage: Observer[Int],
    busy: Signal[Boolean],
  ): HtmlElement = {
    val atFirst = current.map(_ <= 0).distinct
    val atLast  = current.combineWithFn(pageCount)((page, count) => page >= count - 1).distinct

    div(
      cls := "join",
      arrow(UiKeys.commonPreviousPage, "«", atFirst, busy, current.map(_ - 1), onPage),
      children <--
        current
          .combineWithFn(pageCount)((page, count) => pageItems(count, page))
          .distinct
          .map(items => items.map(renderPageItem(_, current, onPage, busy))),
      arrow(UiKeys.commonNextPage, "»", atLast, busy, current.map(_ + 1), onPage),
    )
  }

  private def renderPageItem(
    item: Option[Int],
    current: Signal[Int],
    onPage: Observer[Int],
    busy: Signal[Boolean],
  ): HtmlElement = {
    item match {
      case Some(index) =>
        button(
          cls := "join-item btn btn-sm",
          // `btn-active` is what daisyUI marks the current page with; `aria-current` is what says so to a reader that
          // cannot see the styling.
          cls("btn-active") <-- current.map(_ == index).distinct,
          aria.current.maybe <-- current.map(page => Option.when(page == index)("page")),
          typ := "button",
          disabled <-- busy,
          (index + 1).toString,
          onClick.mapTo(index) --> onPage,
        )
      case None        =>
        button(cls := "join-item btn btn-sm btn-disabled", typ := "button", disabled := true, "…")
    }
  }

  private def arrow(
    labelKey: String,
    glyph: String,
    off: Signal[Boolean],
    busy: Signal[Boolean],
    target: Signal[Int],
    onPage: Observer[Int],
  ): HtmlElement = {
    button(
      cls := "join-item btn btn-sm",
      typ := "button",
      aria.label := I18n.t(labelKey),
      disabled <-- off.combineWithFn(busy)(_ || _).distinct,
      glyph,
      onClick.compose(_.sample(target)) --> onPage,
    )
  }
}
