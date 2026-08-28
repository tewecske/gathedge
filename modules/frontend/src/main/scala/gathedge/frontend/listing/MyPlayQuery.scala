package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{GamePlaySort, Paging}

/** Everything that decides which of the caller's own plays the server sends back — the cross-game counterpart of
  * [[GamePlayQuery]], for `GET /api/games/plays/mine`. Same four paging questions plus a `search`: here it is a
  * substring of the game's name, not the player's address, since every row is always the caller. The sort vocabulary is
  * shared with the owner-facing listing ([[GamePlaySort]]) — the columns are the same, and the game name is filterable
  * only, the same split [[GamePlayQuery]] draws for its player column.
  */
final case class MyPlayQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
) {

  /** Any change other than turning the page starts again at the first one — see [[UserQuery.reset]]. */
  def reset(change: MyPlayQuery => MyPlayQuery): MyPlayQuery = change(this).copy(page = Paging.firstPage)

  /** Whether this query is the previous one with the game filter typed out further — see [[UserQuery.refines]]. */
  def refines(previous: MyPlayQuery): Boolean = {
    search.nonEmpty && previous.search.nonEmpty && search != previous.search &&
    copy(search = "") == previous.copy(search = "")
  }
}

object MyPlayQuery {

  /** The unfiltered listing — the one addressed by `/games/history` with no query string at all. */
  val default: MyPlayQuery = MyPlayQuery()

  private type Args = (Option[Int], Option[Int], Option[String], Option[String], Option[String])

  private val codec: Codec[Args, MyPlayQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search) = args
        MyPlayQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, GamePlaySort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
        )
      },
      (query: MyPlayQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (page, size, sort, direction, Option(query.search).filter(_.nonEmpty))
      },
    )
  }

  /** The query half of `/games/history`. `q` is the game-name filter: a substring of the address, matched
    * case-insensitively by the server.
    */
  val params = (ListingParams.common & param[String]("q").?).as[MyPlayQuery](using codec)
}
