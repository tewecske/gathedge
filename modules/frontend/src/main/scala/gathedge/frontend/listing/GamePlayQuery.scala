package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{GamePlaySort, Paging}

/** Everything that decides which plays of one game the server sends back — the `GamePlaySort` counterpart to
  * [[UserQuery]], for `GET /api/games/{slug}/plays`. The game's `slug` itself is not part of this: it is the route's
  * path segment, not its query, the same split [[UserQuery]] draws between path and query for every other listing.
  */
final case class GamePlayQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
) {

  /** Any change other than turning the page starts again at the first one — see [[UserQuery.reset]]. */
  def reset(change: GamePlayQuery => GamePlayQuery): GamePlayQuery = change(this).copy(page = Paging.firstPage)

  /** Whether this query is the previous one with the player filter typed out further — see [[UserQuery.refines]]. */
  def refines(previous: GamePlayQuery): Boolean = {
    search.nonEmpty && previous.search.nonEmpty && search != previous.search &&
    copy(search = "") == previous.copy(search = "")
  }
}

object GamePlayQuery {

  /** The unfiltered listing — the one addressed by `/games/{slug}/results` with no query string at all. */
  val default: GamePlayQuery = GamePlayQuery()

  private type Args = (Option[Int], Option[Int], Option[String], Option[String], Option[String])

  private val codec: Codec[Args, GamePlayQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search) = args
        GamePlayQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, GamePlaySort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
        )
      },
      (query: GamePlayQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (page, size, sort, direction, Option(query.search).filter(_.nonEmpty))
      },
    )
  }

  /** The query half of `/games/{slug}/results`. `q` is the player filter: a substring of the address, matched
    * case-insensitively by the server.
    */
  val params = (ListingParams.common & param[String]("q").?).as[GamePlayQuery](using codec)
}
