package gathedge.frontend.listing

import com.raquo.waypoint._
import org.scalajs.dom
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.domain.{PartOfSpeech, TranslationFilter, WordLanguage}
import gathedge.shared.dto.{Paging, WordSort}

/** Everything that decides which words the server sends back, in one value — the sibling of [[UserQuery]], and the
  * argument of the `/words` route.
  *
  * Two of its fields are not filters at all. `language` is which language is being *browsed* and `target` which one the
  * translations are shown in; together they are the direction a reader is learning, and putting them in the URL is what
  * makes `de → hu` a link somebody can bookmark or send.
  *
  * `tagId` narrows the listing to one tag, and that is '''all''' it does. Where a tick files a word is a separate,
  * page-local choice remembered in `localStorage` (see `WordCollect.storedCollectTag`) — the two were one field, and
  * one select, until narrowing to `lesson1` was found to silently redirect every subsequent tick into it. The filter is
  * in the URL because it is a view of the data worth bookmarking and sending; the collect tag is working state nobody
  * wants to send anybody.
  */
final case class WordQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
  language: WordLanguage = WordLanguage.De,
  target: WordLanguage = WordLanguage.Hu,
  partOfSpeech: Option[PartOfSpeech] = None,
  tagId: Option[Long] = None,
  mine: Boolean = false,
  translationFilter: TranslationFilter = TranslationFilter.All,
  mainOnly: Boolean = false,
) {

  /** Any change other than turning the page starts again at the first one: page 4 of the old listing says nothing about
    * the new one.
    */
  def reset(change: WordQuery => WordQuery): WordQuery = change(this).copy(page = Paging.firstPage)

  /** Whether this query is the previous one with the search term typed out further — "hau" after "ha".
    *
    * Decides whether the change gets a history entry of its own: everything else pushes, a refinement replaces, so the
    * back button leaves the search rather than walking backwards through the reader's own keystrokes. The rule is
    * [[UserQuery.refines]]'s, unchanged.
    */
  def refines(previous: WordQuery): Boolean = {
    search.nonEmpty && previous.search.nonEmpty && search != previous.search &&
    copy(search = "") == previous.copy(search = "")
  }

  /** This query's filters alone — direction, part of speech, tag, ownership, translation completeness, main-word-only —
    * with paging, sorting and the search term reset to their defaults. What [[WordQuery.storeFilter]] persists: a stale
    * page number or a stale search term is worse than none, but the direction and filters a reader left the listing in
    * are worth remembering the next time they open it cold.
    */
  def filterOnly: WordQuery =
    copy(page = Paging.firstPage, pageSize = Paging.defaultPageSize, sort = SortHeader.Sort.unsorted, search = "")
}

object WordQuery {

  /** The listing addressed by the bare path, with no query string at all: German words with Hungarian translations,
    * which is the direction this deployment's first reader is learning. Changing it changes what `/words` means, so it
    * is a default rather than a preference — a reader who wants another pair links to it.
    */
  val default: WordQuery = WordQuery()

  /** Where the reader's filters are remembered across visits — see [[storedFilter]]/[[storeFilter]]. Distinct from
    * [[gathedge.frontend.components.WordCollect.collectStorageKey]]: that one is the tag a tick files into, this one is
    * the listing's own filters, and the two must not collide.
    */
  private val filterStorageKey = "words.filter"

  /** The filters remembered from the last visit, or `None` on a fresh browser, disabled storage, or a value that no
    * longer decodes. Wrapped like `WordCollect.storedCollectTag`: the storage API throws rather than returns `null`
    * when disabled, and a remembered filter is not worth failing a page load over.
    */
  def storedFilter: Option[WordQuery] = {
    try {
      Option(dom.window.localStorage.getItem(filterStorageKey))
        .flatMap(params.matchQueryString(_).toOption)
        .map(_.filterOnly)
    } catch { case _: Throwable => None }
  }

  /** The target language the listing was last left in, or the one it shows on a browser that remembers nothing.
    *
    * Read by `WordDetailPage`, which has no query of its own: a reader browsing `de → hu` who clicks a word finds the
    * add-a-translation form already set to Hungarian, rather than to whichever of the word's two other languages
    * happens to come first.
    */
  def storedTarget: WordLanguage = storedFilter.map(_.target).getOrElse(default.target)

  /** Remembers this query's filters (see [[WordQuery.filterOnly]]) for the next cold visit. Called on every listing
    * change, not only a filter one — paging and searching produce the same [[filterOnly]] as before, so this simply
    * rewrites the same value until a filter actually changes.
    */
  def storeFilter(query: WordQuery): Unit = {
    try dom.window.localStorage.setItem(filterStorageKey, params.createParamsString(query.filterOnly))
    catch { case _: Throwable => () }
  }

  private type Args = (
    Option[Int],
    Option[Int],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
  )

  private val codec: Codec[Args, WordQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search, language, target, pos, tag, mine, tr, main) = args
        WordQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, WordSort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
          // An unreadable language falls back to the default rather than failing the route, the same way an unknown
          // sort column is dropped: a stale link should still open a list of words.
          language = language.flatMap(WordLanguage.fromString).getOrElse(default.language),
          target = target.flatMap(WordLanguage.fromString).getOrElse(default.target),
          partOfSpeech = pos.flatMap(PartOfSpeech.fromString),
          // Carried as a string because url-dsl has no `FromString[Long, ?]`, so `?tag=nonsense` opens the unfiltered
          // page instead of no page at all — the arrangement `AuditQuery` uses for its actor filter.
          tagId = tag.flatMap(_.toLongOption),
          mine = mine.contains("true"),
          translationFilter = tr.flatMap(TranslationFilter.fromString).getOrElse(default.translationFilter),
          mainOnly = main.contains("true"),
        )
      },
      (query: WordQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (
          page,
          size,
          sort,
          direction,
          Option(query.search).filter(_.nonEmpty),
          Option.when(query.language != default.language)(WordLanguage.code(query.language)),
          Option.when(query.target != default.target)(WordLanguage.code(query.target)),
          query.partOfSpeech.map(PartOfSpeech.code),
          query.tagId.map(_.toString),
          Option.when(query.mine)("true"),
          Option.when(query.translationFilter != default.translationFilter)(
            TranslationFilter.code(query.translationFilter)
          ),
          Option.when(query.mainOnly)("true"),
        )
      },
    )
  }

  /** The query half of `/words`. */
  val params = {
    (
      ListingParams.common & param[String]("q").? & param[String]("lang").? & param[String]("target").? &
        param[String]("pos").? & param[String]("tag").? & param[String]("mine").? & param[String]("tr").? &
        param[String]("main").?
    ).as[WordQuery](using codec)
  }
}
