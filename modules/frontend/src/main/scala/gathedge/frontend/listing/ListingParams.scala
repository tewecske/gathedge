package gathedge.frontend.listing

import com.raquo.waypoint._
import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{Paging, SortDirection}

/** The four query parameters every paged listing puts in its URL, and the rules for reading them back out.
  *
  * They are shared because they are the same four questions for any listing — which page, how big, ordered by what,
  * which way — so a third listing needs only its own case class, its own extra parameters, and one route. What is *not*
  * shared is the meaning of `sort`: each listing knows its own columns, so [[decodeSort]] is told which ones.
  */
object ListingParams {

  /** `page`, `size`, `sort`, `dir`, in the order the tuple below is in.
    *
    * Every one is optional, which is what keeps a bookmark honest: a parameter appears only when the reader chose
    * something other than the default, so the address holds the choices and nothing else.
    */
  val common = {
    param[Int]("page").? & param[Int]("size").? & param[String]("sort").? & param[String]("dir").?
  }

  type Common = (Option[Int], Option[Int], Option[String], Option[String])

  def encodeCommon(page: Int, pageSize: Int, sort: SortHeader.Sort): Common = {
    (
      // One-based, like the API and like the buttons: the second page is `page=2`, and the first writes no parameter
      // at all, so the unpaged listing keeps a clean address.
      Option.when(page != Paging.firstPage)(page),
      Option.when(pageSize != Paging.defaultPageSize)(pageSize),
      // Both already answer `None` for an unsorted table, so an unordered listing writes neither parameter.
      sort.column,
      sort.wire,
    )
  }

  /** A URL is hand-editable, so both bounds are applied on the way in.
    *
    * `pageSize` reaches the SQL `LIMIT`, and the server clamps it as well ([[Paging.boundedPageSize]] is the same value
    * on both sides) — doing it here too is what keeps the address bar and the control in agreement, rather than showing
    * 20 rows under a URL that says `size=100000`.
    */
  /** `?page=0`, or anything below the first page, reads as the first page rather than as an error. */
  def decodePage(page: Option[Int]): Int = Paging.boundedPage(page)

  def decodePageSize(size: Option[Int]): Int = Paging.boundedPageSize(size)

  /** An unknown column is dropped rather than refused.
    *
    * The server already falls through to the listing's own order for a value it does not recognise, so a stale link
    * still answers with rows. Dropping it here means the heading and the URL agree about what is ordered — keeping it
    * would leave a sort in the address that no column draws a glyph for.
    */
  def decodeSort(column: Option[String], direction: Option[String], columns: List[String]): SortHeader.Sort = {
    column.filter(columns.contains) match {
      case Some(value) =>
        SortHeader.Sort(Some(value), SortDirection.isDescending(direction))
      case None        =>
        SortHeader.Sort.unsorted
    }
  }

  /** A parameter somebody typed by hand can be blank; blank is the same as absent. */
  def decodeText(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)
}
