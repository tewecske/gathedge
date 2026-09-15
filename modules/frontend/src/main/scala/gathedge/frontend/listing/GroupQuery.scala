package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{GroupSort, Paging}

/** Everything that decides which groups the server sends back, in one value — the same reasoning [[UserQuery]] carries,
  * with a second debounced filter alongside the name search: which group holds a tag matching `tag`.
  *
  * It lives here rather than in the page because it is also the argument of a route — see [[GroupQuery.params]].
  */
final case class GroupQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
  tag: String = "",
) {

  /** Any change other than turning the page starts again at the first one, the same rule [[UserQuery.reset]] follows.
    */
  def reset(change: GroupQuery => GroupQuery): GroupQuery = change(this).copy(page = Paging.firstPage)

  /** Whether this query is the previous one with *either* box typed out further — "bo" after "b", in the name or the
    * tag filter. Both terms have to be non-empty for the field that changed, and the query with both filters cleared
    * has to match the previous one with both cleared too, so a search that also picks up a fresh tag term (or vice
    * versa) is a new query, not a refinement of the old one — the same "nothing else may differ" rule
    * [[UserQuery.refines]] follows for its one filter.
    */
  def refines(previous: GroupQuery): Boolean = {
    def longer(current: String, prior: String): Boolean = current.nonEmpty && prior.nonEmpty && current != prior

    (longer(search, previous.search) || longer(tag, previous.tag)) &&
    copy(search = "", tag = "") == previous.copy(search = "", tag = "")
  }
}

object GroupQuery {

  /** The unfiltered listing — the one addressed by the bare path, with no query string at all. */
  val default: GroupQuery = GroupQuery()

  private type Args = (Option[Int], Option[Int], Option[String], Option[String], Option[String], Option[String])

  private val codec: Codec[Args, GroupQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search, tag) = args
        GroupQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, GroupSort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
          tag = ListingParams.decodeText(tag).getOrElse(""),
        )
      },
      (query: GroupQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (page, size, sort, direction, Option(query.search).filter(_.nonEmpty), Option(query.tag).filter(_.nonEmpty))
      },
    )
  }

  /** The query half of `/groups`. `q` is the name filter; `tag` narrows to groups holding an attached tag whose name
    * contains it. Both are substrings, matched case-insensitively by the server.
    */
  val params = {
    (ListingParams.common & param[String]("q").? & param[String]("tag").?).as[GroupQuery](using codec)
  }
}
