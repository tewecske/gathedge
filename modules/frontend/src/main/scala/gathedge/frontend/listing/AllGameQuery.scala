package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{AllGameSort, Paging}

/** Everything that decides which games the server sends back — the games-listing counterpart of [[MyPlayQuery]], for
  * `GET /api/games/all`. Same four paging questions, a `search` (a substring of the game's own name), and a
  * `favoritesOnly` toggle that keeps only the games the caller has favorited. The sort vocabulary is its own
  * ([[AllGameSort]]): the name, the creation date, and the like count — the rest are labels or an aggregate over a
  * different table.
  */
final case class AllGameQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
  favoritesOnly: Boolean = false,
) {

  /** Any change other than turning the page starts again at the first one — see [[UserQuery.reset]]. */
  def reset(change: AllGameQuery => AllGameQuery): AllGameQuery = change(this).copy(page = Paging.firstPage)

  /** Whether this query is the previous one with the name filter typed out further — see [[UserQuery.refines]]. */
  def refines(previous: AllGameQuery): Boolean = {
    search.nonEmpty && previous.search.nonEmpty && search != previous.search &&
    copy(search = "") == previous.copy(search = "")
  }
}

object AllGameQuery {

  /** The unfiltered listing — the one addressed by `/games/all` with no query string at all. */
  val default: AllGameQuery = AllGameQuery()

  private type Args = (Option[Int], Option[Int], Option[String], Option[String], Option[String], Option[Boolean])

  private val codec: Codec[Args, AllGameQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search, favorites) = args
        AllGameQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, AllGameSort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
          favoritesOnly = favorites.getOrElse(false),
        )
      },
      (query: AllGameQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (page, size, sort, direction, Option(query.search).filter(_.nonEmpty), Option.when(query.favoritesOnly)(true))
      },
    )
  }

  /** The query half of `/games/all`. `q` is the name filter; `fav=true` is the "my favorites" toggle. Both appear only
    * when chosen, so the unfiltered listing keeps a clean address.
    */
  val params =
    (ListingParams.common & param[String]("q").? & param[Boolean]("fav").?).as[AllGameQuery](using codec)
}
