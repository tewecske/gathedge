package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.domain.WordLanguage
import gathedge.shared.dto.{AllGameSort, Paging}

/** Everything that decides which games the server sends back — the games-listing counterpart of [[MyPlayQuery]], for
  * `GET /api/games/all`. Same four paging questions, a `search` (a substring of the game's own name), and a
  * `favoritesOnly` toggle that keeps only the games the caller has favorited. The sort vocabulary is its own
  * ([[AllGameSort]]): the name, the creation date, and the like count — the rest are labels or an aggregate over a
  * different table.
  *
  * `tagId` narrows to one wordlist. `language1`/`language2` narrow to games whose language pair contains whichever of
  * the two are chosen — order does not matter for which games match (see `GameService.allGames`'s doc comment), but the
  * order they were chosen in still steers `TagPicker`'s suggestion order, so it is kept as two named slots rather than
  * a `Set`. Choosing a tag locks both to that tag's own pair — see `AllGamesPage.languagesLockedSignal`.
  */
final case class AllGameQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
  favoritesOnly: Boolean = false,
  tagId: Option[Long] = None,
  language1: Option[WordLanguage] = None,
  language2: Option[WordLanguage] = None,
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

  private type Args = (
    Option[Int],
    Option[Int],
    Option[String],
    Option[String],
    Option[String],
    Option[Boolean],
    Option[Long],
    Option[String],
    Option[String],
  )

  private val codec: Codec[Args, AllGameQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search, favorites, tag, lang1, lang2) = args
        AllGameQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, AllGameSort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
          favoritesOnly = favorites.getOrElse(false),
          tagId = tag,
          language1 = lang1.flatMap(WordLanguage.fromString),
          language2 = lang2.flatMap(WordLanguage.fromString),
        )
      },
      (query: AllGameQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (
          page,
          size,
          sort,
          direction,
          Option(query.search).filter(_.nonEmpty),
          Option.when(query.favoritesOnly)(true),
          query.tagId,
          query.language1.map(WordLanguage.code),
          query.language2.map(WordLanguage.code),
        )
      },
    )
  }

  /** The query half of `/games/all`. `q` is the name filter; `fav=true` is the "my favorites" toggle; `tag` is the
    * wordlist filter; `lang1`/`lang2` are the language filter. All appear only when chosen, so the unfiltered listing
    * keeps a clean address.
    */
  val params = {
    (
      ListingParams.common & param[String]("q").? & param[Boolean]("fav").? & param[Long]("tag").? &
        param[String]("lang1").? & param[String]("lang2").?
    ).as[AllGameQuery](using codec)
  }
}
