package gathedge.frontend.listing

import gathedge.frontend.components.SortHeader
import gathedge.shared.domain.{TranslationFilter, WordLanguage}
import gathedge.shared.dto.{Paging, WordSort}
import zio.test._

/** The two rules a listing query has to keep, stated for the vocabulary's own: when the page index resets, and what
  * counts as merely typing further.
  */
object WordQuerySpec extends ZIOSpecDefault {

  def spec = {
    suite("WordQuery")(
      // Page 4 of the old listing says nothing about the new one, whatever the change was.
      test("every change but a page turn returns to the first page") {
        val onPageFour = WordQuery(page = 4)
        assertTrue(
          onPageFour.reset(_.copy(search = "hau")).page == Paging.firstPage,
          onPageFour.reset(_.copy(target = WordLanguage.En)).page == Paging.firstPage,
          onPageFour.reset(_.copy(tagId = Some(3L))).page == Paging.firstPage,
          onPageFour.reset(_.copy(translationFilter = TranslationFilter.HasTarget)).page == Paging.firstPage,
          onPageFour.reset(_.copy(sort = SortHeader.Sort.ascending(WordSort.text))).page == Paging.firstPage,
          // Turning the page is the one write that does not go through `reset`.
          onPageFour.copy(page = 5).page == 5,
        )
      },
      // What decides whether a change gets a history entry: a refinement replaces, so the back button leaves the
      // search rather than walking backwards through the reader's own keystrokes.
      test("a longer search term refines the one before it, and nothing else does") {
        val typed = WordQuery(search = "hau")
        assertTrue(
          typed.refines(WordQuery(search = "ha")),
          // The first search is what the entry behind it is for, and clearing the box is worth returning to.
          !typed.refines(WordQuery(search = "")),
          !WordQuery(search = "").refines(typed),
          // A query never refines itself.
          !typed.refines(typed),
          // Something else changed as well, so this is a navigation rather than more typing.
          !typed.copy(target = WordLanguage.En).refines(WordQuery(search = "ha")),
          !typed.copy(mine = true).refines(WordQuery(search = "ha")),
        )
      },
      test("the default query is the one the bare path addresses") {
        assertTrue(
          WordQuery.default == WordQuery(),
          WordQuery.default.language == WordLanguage.De,
          WordQuery.default.target == WordLanguage.Hu,
          WordQuery.default.translationFilter == TranslationFilter.All,
          !WordQuery.default.mine,
        )
      },
    )
  }
}
