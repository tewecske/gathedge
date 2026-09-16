package gathedge.frontend.listing

import urldsl.vocabulary.Codec
import gathedge.shared.dto.Paging

/** Which page of one wordlist's rows the editor shows — the argument of the `/tags/{id}` route, and the smallest of
  * these listing queries: two numbers and nothing else.
  *
  * It carries no `sort` and no `dir`, unlike [[TagQuery]] and the rest. The editor's rows are in the order they were
  * added (a bulk import keeps the pasted text's order, which is the reader's own), so no heading orders them and there
  * is nothing for those two parameters to say. Its six provenance filters stay page-local `Var`s rather than joining
  * this: they are read off each row's import flags, which is a question about the rows already in the browser, not a
  * narrowing the server could answer.
  *
  * It also differs from every other listing query in what turns the page. The rows of one wordlist arrive in one answer
  * — `WordEndpoints.tagEntries` is not paged, and the editor needs the whole list anyway: to refuse a duplicate pair,
  * to lock the language pair while any row holds an answer, and to drop a deleted row without asking again — so `page`
  * cuts what is drawn rather than what is fetched. It is still in the URL, for the reason every other listing's is: a
  * page of a long wordlist is a place a reader can be sent back to.
  */
final case class TagEntryQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.tagEntryPageSize,
) {

  /** Any change other than turning the page starts again at the first one — the rule [[TagQuery.reset]] follows. Here
    * the change is often no change at all (`reset(identity)`): a filter chip narrows the rows without touching this
    * query, and page 4 of the wider list says nothing about the narrowed one.
    */
  def reset(change: TagEntryQuery => TagEntryQuery): TagEntryQuery = change(this).copy(page = Paging.firstPage)
}

object TagEntryQuery {

  /** The first page at the editor's own page size — what `/tags/{id}` means with no query string at all. */
  val default: TagEntryQuery = TagEntryQuery()

  private val codec: Codec[ListingParams.Paged, TagEntryQuery] = {
    Codec.factory(
      (args: ListingParams.Paged) => {
        val (page, size) = args
        TagEntryQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size, Paging.tagEntryPageSize),
        )
      },
      (query: TagEntryQuery) => ListingParams.encodePaging(query.page, query.pageSize, Paging.tagEntryPageSize),
    )
  }

  /** The query half of `/tags/{id}`. */
  val params = ListingParams.paging.as[TagEntryQuery](using codec)
}
