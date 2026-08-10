package webapp1.frontend.components

import com.raquo.laminar.api.L._
import webapp1.frontend.i18n.I18n
import webapp1.shared.i18n.UiKeys

/** The page selector under a long table, plus the control that says how many rows a page holds.
  *
  * Purely presentational, in the shape the laminar skill asks for: it reads the current page and the page size as
  * `Signal`s and writes requests back through `Observer`s, so it owns none of the state and neither caller has to model
  * its state the same way. That matters here because the two callers are not alike — the user list holds every row it
  * will ever show and knows its page count exactly, while the audit log pages backwards through a cursor and only ever
  * knows how many pages it has *fetched*. [[render]]'s `hasMore` is the seam between those two: it says the next arrow
  * may point one past the last known page, and it is then the caller's business to make that page exist.
  */
object Pagination {

  /** What a page holds until somebody says otherwise, and what they may say instead. */
  val defaultPageSize: Int = 20
  val pageSizes: List[Int] = List(20, 50, 100)

  /** How many numbered buttons the row may hold, ellipses included. Beyond this the middle is elided rather than let a
    * table with a hundred pages push its own controls off the side of the screen.
    */
  private val maxButtons = 7

  def pageCount(total: Int, pageSize: Int): Int = {
    if (total <= 0)
      0
    else
      (total + pageSize - 1) / pageSize
  }

  /** The index of the last page, or `0` when there is nothing to show. What a caller jumps to when it has just added
    * rows at the end and wants them on screen.
    */
  def lastPage(total: Int, pageSize: Int): Int = {
    math.max(0, pageCount(total, pageSize) - 1)
  }

  /** A page index the caller can safely use, whatever it was holding. A list that shrinks — a filter narrowing, a row
    * deleted — leaves the stored index pointing past the end, and clamping on read means no page has to watch for that
    * and write a corrected index back.
    */
  def clampPage(page: Int, pageCount: Int): Int = {
    math.max(0, math.min(page, pageCount - 1))
  }

  /** The rows page `page` shows, clamped the same way. */
  def slice[A](items: List[A], page: Int, pageSize: Int): List[A] = {
    val bounded = clampPage(page, pageCount(items.size, pageSize))
    items.slice(bounded * pageSize, bounded * pageSize + pageSize)
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
    * @param pageCount
    *   how many pages can be numbered. For a cursor-paged caller this is what it has fetched, not what exists.
    * @param onPage
    *   the page the reader asked for. With `hasMore` set this can be `pageCount` itself — one past the last numbered
    *   button — which is the caller's cue to fetch.
    * @param hasMore
    *   whether the next arrow stays live on the last numbered page.
    * @param busy
    *   disables the whole control while a request is out, so a second click cannot race the first.
    */
  def render(
    page: Signal[Int],
    pageCount: Signal[Int],
    onPage: Observer[Int],
    pageSize: Signal[Int],
    onPageSize: Observer[Int],
    hasMore: Signal[Boolean] = Val(false),
    busy: Signal[Boolean] = Val(false),
  ): HtmlElement = {
    val current = page.combineWithFn(pageCount)(clampPage).distinct

    div(
      cls := "flex flex-wrap items-center justify-between gap-4 mt-4",
      renderPageSize(pageSize, onPageSize, busy),
      renderPages(current, pageCount, onPage, hasMore, busy),
    )
  }

  private def renderPageSize(pageSize: Signal[Int], onPageSize: Observer[Int], busy: Signal[Boolean]): HtmlElement = {
    label(
      cls := "flex items-center gap-2",
      span(cls := "label-text text-xs", I18n.t(UiKeys.commonRowsPerPage)),
      select(
        cls := "select select-sm w-auto",
        disabled <-- busy,
        pageSizes.map(size => option(value := size.toString, size.toString)),
        controlled(
          value <-- pageSize.map(_.toString).distinct,
          // A value this `select` did not offer cannot arrive, so anything unparseable is a bug rather than input.
          onChange.mapToValue.map(_.toIntOption.getOrElse(defaultPageSize)) --> onPageSize,
        ),
      ),
    )
  }

  private def renderPages(
    current: Signal[Int],
    pageCount: Signal[Int],
    onPage: Observer[Int],
    hasMore: Signal[Boolean],
    busy: Signal[Boolean],
  ): HtmlElement = {
    val atFirst = current.map(_ <= 0).distinct
    val atLast  = {
      current
        .combineWith(pageCount, hasMore)
        .map((page, count, more) => !more && page >= count - 1)
        .distinct
    }

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
