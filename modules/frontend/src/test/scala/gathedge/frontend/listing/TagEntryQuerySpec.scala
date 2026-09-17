package gathedge.frontend.listing

import gathedge.shared.domain.{EntryBucket, TagEntryFilter}
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
          // A chip is a toggle, and every toggle starts the narrowed listing at its own first page.
          TagEntryQuery(page = 4).toggleBucket(EntryBucket.Paired) ==
            TagEntryQuery(buckets = Set(EntryBucket.Paired)),
          TagEntryQuery(page = 4, buckets = Set(EntryBucket.Paired)).toggleBucket(EntryBucket.Paired) ==
            TagEntryQuery.default,
        )
      },
      // What the editor asks the endpoint for, and what the database then narrows the page and the count by.
      test("its three chips are the filter the endpoint takes") {
        val narrowed = TagEntryQuery(buckets = Set(EntryBucket.Verified), importedByMe = true)
        assertTrue(
          TagEntryQuery.default.filter == TagEntryFilter.none,
          TagEntryQuery.default.filter.isEmpty,
          narrowed.filter == TagEntryFilter(Set(EntryBucket.Verified), importedByMe = true, uniqueToTag = false),
        )
      },
      // The parameters a reader can hand-edit, bounded on the way in like every other listing's.
      test("its URL parameters round-trip, and a nonsensical one is bounded") {
        val second   = TagEntryQuery(page = 2, pageSize = 10)
        val narrowed = TagEntryQuery(buckets = Set(EntryBucket.Verified, EntryBucket.Other), uniqueToTag = true)
        assertTrue(
          TagEntryQuery.params.matchQueryString(TagEntryQuery.params.createParamsString(narrowed)).contains(narrowed),
          TagEntryQuery.params.createParamsString(narrowed).contains("match=verified%2Cother"),
          TagEntryQuery.params.createParamsString(narrowed).contains("unique=true"),
          // A chip nobody recognises is dropped rather than refusing the route.
          TagEntryQuery.params.matchQueryString("match=shoe-size").contains(TagEntryQuery.default),
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
