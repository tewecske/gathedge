package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{MyGameSort, Paging}

/** Everything that decides which of the caller's own games the server sends back — the "my games" counterpart of
  * [[MyPlayQuery]], for `GET /api/games/mine`. Same four paging questions plus a `search`: here it is a substring of
  * the game's own name. The sort vocabulary is its own ([[MyGameSort]]): only the name and the creation date order,
  * since the play count is an aggregate this listing does not join and the rest are labels.
  */
final case class MyGameQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
) {

  /** Any change other than turning the page starts again at the first one — see [[UserQuery.reset]]. */
  def reset(change: MyGameQuery => MyGameQuery): MyGameQuery = change(this).copy(page = Paging.firstPage)

  /** Whether this query is the previous one with the name filter typed out further — see [[UserQuery.refines]]. */
  def refines(previous: MyGameQuery): Boolean = {
    search.nonEmpty && previous.search.nonEmpty && search != previous.search &&
    copy(search = "") == previous.copy(search = "")
  }
}

object MyGameQuery {

  /** The unfiltered listing — the one addressed by `/games/mine` with no query string at all. */
  val default: MyGameQuery = MyGameQuery()

  private type Args = (Option[Int], Option[Int], Option[String], Option[String], Option[String])

  private val codec: Codec[Args, MyGameQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search) = args
        MyGameQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, MyGameSort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
        )
      },
      (query: MyGameQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (page, size, sort, direction, Option(query.search).filter(_.nonEmpty))
      },
    )
  }

  /** The query half of `/games/mine`. `q` is the name filter: a substring of the game's name, matched
    * case-insensitively by the server.
    */
  val params = (ListingParams.common & param[String]("q").?).as[MyGameQuery](using codec)
}
