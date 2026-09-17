package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.shared.domain.{EntryBucket, TagEntryFilter}
import gathedge.shared.dto.Paging

/** Which rows of one wordlist the editor shows — the argument of the `/tags/{id}` route, and everything
  * `GET /api/tags/{tagId}/entries/page` is asked.
  *
  * It carries no `sort` and no `dir`, unlike [[TagQuery]] and the rest. The editor's rows are in the order they were
  * added (a bulk import keeps the pasted text's order, which is the reader's own), so no heading orders them and there
  * is nothing for those two parameters to say. What it does carry that no other listing does is the provenance chips,
  * because they are this listing's filter: the database narrows the page and the count by them, so they belong in the
  * address beside the page number rather than in a `Var` the URL knows nothing about.
  *
  * A page is `pageSize` *source words*, not rows — a word with two marked answers brings both — so `total` counts words
  * and the editor's summary says so. See `dto.TagEntryPage`.
  */
final case class TagEntryQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.tagEntryPageSize,
  buckets: Set[EntryBucket] = Set.empty,
  importedByMe: Boolean = false,
  uniqueToTag: Boolean = false,
) {

  /** Any change other than turning the page starts again at the first one — the rule [[TagQuery.reset]] follows. Page 4
    * of the wider list says nothing about the narrowed one.
    */
  def reset(change: TagEntryQuery => TagEntryQuery): TagEntryQuery = change(this).copy(page = Paging.firstPage)

  /** The three filters as the API takes them, which is also how the database reads them. */
  def filter: TagEntryFilter = TagEntryFilter(buckets, importedByMe, uniqueToTag)

  /** The chip a reader just pressed, on or off — every chip is a toggle, and every toggle is a new listing. */
  def toggleBucket(bucket: EntryBucket): TagEntryQuery = {
    reset(query => query.copy(buckets = if (buckets.contains(bucket)) buckets - bucket else buckets + bucket))
  }
}

object TagEntryQuery {

  /** The unfiltered first page at the editor's own page size — what `/tags/{id}` means with no query string at all. */
  val default: TagEntryQuery = TagEntryQuery()

  private type Args = (Option[Int], Option[Int], Option[String], Option[String], Option[String])

  private val codec: Codec[Args, TagEntryQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, matchKinds, mine, unique) = args
        TagEntryQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size, Paging.tagEntryPageSize),
          buckets = TagEntryFilter.parse(matchKinds),
          importedByMe = mine.contains("true"),
          uniqueToTag = unique.contains("true"),
        )
      },
      (query: TagEntryQuery) => {
        val (page, size) = ListingParams.encodePaging(query.page, query.pageSize, Paging.tagEntryPageSize)
        (
          page,
          size,
          Option(TagEntryFilter.codes(query.buckets)).filter(_.nonEmpty),
          Option.when(query.importedByMe)("true"),
          Option.when(query.uniqueToTag)("true"),
        )
      },
    )
  }

  /** The query half of `/tags/{id}`. `match` is the chip row, comma-joined; `mine` and `unique` are the two toggles
    * beside it — the same names and the same spellings the endpoint takes, so the address and the request read alike.
    */
  val params = {
    (ListingParams.paging & param[String]("match").? & param[String]("mine").? & param[String]("unique").?)
      .as[TagEntryQuery](using codec)
  }
}
