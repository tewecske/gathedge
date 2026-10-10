package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.domain.{TagScope, WordLanguage}
import gathedge.shared.dto.{Paging, TagSort}

/** Everything that decides which wordlists `GET /api/tags/page` sends back — the sibling of [[UserQuery]], and the
  * argument of the `/tags` route.
  *
  * `scope` is what used to be `TagsPage`'s section headings (own wordlists, a study group's, everyone else's): a flat
  * paged table has no heading left to carry that distinction, so it is a filter instead. Left at [[TagScope.All]] and
  * unsorted, the listing keeps the same precedence the headings did — see `WordService.listTagsPaged`'s doc comment.
  *
  * `language1`/`language2` narrow to wordlists whose language pair contains whichever of the two are chosen, in either
  * order — the rule [[AllGameQuery]]'s two slots follow.
  */
final case class TagQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
  scope: TagScope = TagScope.All,
  language1: Option[WordLanguage] = None,
  language2: Option[WordLanguage] = None,
) {

  /** Any change other than turning the page starts again at the first one: page 4 of the old listing says nothing about
    * the new one.
    */
  def reset(change: TagQuery => TagQuery): TagQuery = change(this).copy(page = Paging.firstPage)

  /** Whether a filter narrows the listing — what shows "Reset filters". The search term is not one: it has its own box.
    */
  def narrowed: Boolean = scope != TagScope.All || language1.isDefined || language2.isDefined

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

  private type Args = (
    Option[Int],
    Option[Int],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
  )

  private val codec: Codec[Args, TagQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search, scope, lang1, lang2) = args
        TagQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, TagSort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
          scope = scope.map(TagScope.fromString).getOrElse(TagScope.All),
          language1 = lang1.flatMap(WordLanguage.fromString),
          language2 = lang2.flatMap(WordLanguage.fromString),
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
          query.language1.map(WordLanguage.code),
          query.language2.map(WordLanguage.code),
        )
      },
    )
  }

  /** The query half of `/tags`. `q` is the search box, matched case-insensitively by the server; `scope` is
    * [[TagScope.code]]; `lang1`/`lang2` are the language filter. An unknown language code is dropped.
    */
  val params = {
    (
      ListingParams.common & param[String]("q").? & param[String]("scope").? & param[String]("lang1").? &
        param[String]("lang2").?
    ).as[TagQuery](using codec)
  }
}
