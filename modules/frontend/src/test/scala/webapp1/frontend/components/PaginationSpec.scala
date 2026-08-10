package webapp1.frontend.components

import zio.test._

/** The arithmetic behind the page buttons, which is the part of paging that can be wrong without looking wrong.
  *
  * All of it is pure by design: the component reads signals and writes observers but decides nothing, so every rule
  * about which buttons a page offers can be stated here rather than driven through jsdom. How many pages there *are*
  * is `shared`'s `Paging`, tested next to it — both ends have to agree on that one.
  */
object PaginationSpec extends ZIOSpecDefault {

  def spec = {
    suite("Pagination")(
      test("an empty listing has no last page to jump to") {
        assertTrue(
          Pagination.lastPage(0L, 20) == 0,
          Pagination.lastPage(45L, 20) == 2,
          Pagination.lastPage(45L, 100) == 0,
        )
      },
      // The reason no page needs a correcting write-back when its listing shrinks underneath it — a narrowed search
      // leaves the stored index pointing past the end, and the next response is simply read at the last page.
      test("a page index past the end clamps to the last page rather than showing nothing") {
        assertTrue(
          Pagination.clampPage(9, 3) == 2,
          Pagination.clampPage(-1, 3) == 0,
          Pagination.clampPage(5, 0) == 0,
          Pagination.clampPage(1, 3) == 1,
        )
      },
      test("a short listing offers every page and elides nothing") {
        assertTrue(
          Pagination.pageItems(pageCount = 0, current = 0) == List.empty[Option[Int]],
          Pagination.pageItems(pageCount = 3, current = 1) == List(Some(0), Some(1), Some(2)),
        )
      },
      // The first and last page stay reachable in one click however long the listing gets; the middle collapses.
      test("a long listing keeps the ends and the current page's neighbourhood") {
        assertTrue(
          Pagination.pageItems(pageCount = 20, current = 0) == List(Some(0), Some(1), None, Some(19)),
          Pagination.pageItems(pageCount = 20, current = 10) ==
            List(Some(0), None, Some(9), Some(10), Some(11), None, Some(19)),
          Pagination.pageItems(pageCount = 20, current = 19) == List(Some(0), None, Some(18), Some(19)),
        )
      },
      // Whatever the elision does, the row of buttons has to stay a row of buttons: ascending, no repeats, nothing
      // pointing off the end, and the page being read always among them.
      test("every window is ordered, distinct, in range, and contains the current page") {
        val windows = {
          for {
            count   <- (0 to 25).toList
            current <- (0 until count).toList
          } yield (count, current, Pagination.pageItems(count, current).flatten)
        }

        assertTrue(
          windows.forall { case (_, _, pages) => pages == pages.distinct },
          windows.forall { case (_, _, pages) => pages == pages.sorted },
          windows.forall { case (count, _, pages) => pages.forall(page => page >= 0 && page < count) },
          windows.forall { case (_, current, pages) => pages.contains(current) },
        )
      },
    )
  }
}
