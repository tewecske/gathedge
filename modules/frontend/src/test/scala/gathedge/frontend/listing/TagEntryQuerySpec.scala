package gathedge.frontend.listing

import gathedge.shared.dto.Paging
import zio.test._

/** [[TagEntryQuery]]'s two rules, the smaller half of what [[UserQuerySpec]] states: the editor has no search box to
  * refine, so `reset` is the whole of it — and the page size it starts at is its own, not the one every other listing
  * uses.
  */
object TagEntryQuerySpec extends ZIOSpecDefault {

  def spec = {
    suite("TagEntryQuery")(
      test("the editor starts on the first page, at its own page size") {
        assertTrue(
          TagEntryQuery.default.page == Paging.firstPage,
          TagEntryQuery.default.pageSize == Paging.tagEntryPageSize,
          TagEntryQuery.default.pageSize != Paging.defaultPageSize,
        )
      },
      test("any change but a page turn returns to the first page") {
        assertTrue(
          TagEntryQuery(page = 4).reset(_.copy(pageSize = 10)) == TagEntryQuery(pageSize = 10),
          // A filter chip changes nothing in the query itself and still gives up the page it was on.
          TagEntryQuery(page = 4).reset(identity) == TagEntryQuery.default,
        )
      },
      // The parameters a reader can hand-edit, bounded on the way in like every other listing's.
      test("its URL parameters round-trip, and a nonsensical one is bounded") {
        val second = TagEntryQuery(page = 2, pageSize = 10)
        assertTrue(
          TagEntryQuery.params.matchQueryString(TagEntryQuery.params.createParamsString(second)).contains(second),
          // The first page at the editor's own size writes no parameter at all.
          TagEntryQuery.params.createParamsString(TagEntryQuery.default) == "",
          TagEntryQuery.params.matchQueryString("").contains(TagEntryQuery.default),
          TagEntryQuery.params.matchQueryString("page=0").contains(TagEntryQuery.default),
          TagEntryQuery.params
            .matchQueryString("size=100000")
            .contains(TagEntryQuery(pageSize = Paging.maxPageSize)),
        )
      },
    )
  }
}
