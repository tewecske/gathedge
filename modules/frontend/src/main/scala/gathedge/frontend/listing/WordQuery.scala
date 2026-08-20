package gathedge.frontend.listing

import com.raquo.waypoint._
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
}

object WordQuery {

  /** The listing addressed by the bare path, with no query string at all: German words with Hungarian translations,
    * which is the direction this deployment's first reader is learning. Changing it changes what `/words` means, so it
    * is a default rather than a preference — a reader who wants another pair links to it.
    */
  val default: WordQuery = WordQuery()

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
  )

  private val codec: Codec[Args, WordQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search, language, target, pos, tag, mine, tr) = args
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
        )
      },
    )
  }

  /** The query half of `/words`. */
  val params = {
    (
      ListingParams.common & param[String]("q").? & param[String]("lang").? & param[String]("target").? &
        param[String]("pos").? & param[String]("tag").? & param[String]("mine").? & param[String]("tr").?
    ).as[WordQuery](using codec)
  }
}
