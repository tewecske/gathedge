package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.domain.TagScope
import gathedge.shared.dto.{Paging, TagSort}

/** Everything that decides which wordlists `GET /api/tags/page` sends back — the sibling of [[UserQuery]], and the
  * argument of the `/tags` route.
  *
  * `scope` is what used to be `TagsPage`'s section headings (own wordlists, a study group's, everyone else's): a flat
  * paged table has no heading left to carry that distinction, so it is a filter instead. Left at [[TagScope.All]] and
  * unsorted, the listing keeps the same precedence the headings did — see `WordService.listTagsPaged`'s doc comment.
  */
final case class TagQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
  scope: TagScope = TagScope.All,
) {

  /** Any change other than turning the page starts again at the first one: page 4 of the old listing says nothing about
    * the new one.
    */
  def reset(change: TagQuery => TagQuery): TagQuery = change(this).copy(page = Paging.firstPage)

  /** Whether this query is the previous one with the search term typed out further — the same rule
    * [[UserQuery.refines]] follows.
    */
  def refines(previous: TagQuery): Boolean = {
    search.nonEmpty && previous.search.nonEmpty && search != previous.search &&
    copy(search = "") == previous.copy(search = "")
  }
}

object TagQuery {

  /** The unfiltered listing — the one addressed by the bare path, with no query string at all. */
  val default: TagQuery = TagQuery()

  private type Args = (Option[Int], Option[Int], Option[String], Option[String], Option[String], Option[String])

  private val codec: Codec[Args, TagQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search, scope) = args
        TagQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, TagSort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
          scope = scope.map(TagScope.fromString).getOrElse(TagScope.All),
        )
      },
      (query: TagQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (
          page,
          size,
          sort,
          direction,
          Option(query.search).filter(_.nonEmpty),
          Option.when(query.scope != TagScope.All)(TagScope.code(query.scope)),
        )
      },
    )
  }

  /** The query half of `/tags`. `q` is the search box, matched case-insensitively by the server; `scope` is
    * [[TagScope.code]].
    */
  val params = {
    (ListingParams.common & param[String]("q").? & param[String]("scope").?).as[TagQuery](using codec)
  }
}
